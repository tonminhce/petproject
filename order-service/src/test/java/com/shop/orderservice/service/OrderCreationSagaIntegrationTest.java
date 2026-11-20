package com.shop.orderservice.service;

import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class OrderCreationSagaIntegrationTest extends AbstractOrderServiceIT {

    private UUID userId;
    private UUID productId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        var cart = cartRepository.save(Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productId)
            .productTitle("Test Product").unitPrice(new BigDecimal("100.00")).quantity(2).build());
    }

    @Test
    void happyPath_createsOrderAndPublishesEvent() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test Product","priceUnit":100.00,"currency":"VND"}}
                """.formatted(productId))));

        UUID reservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(reservationId, productId))));

        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-1");

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(orderRepository.findById(response.id())).isPresent();
        assertThat(cartRepository.findByUserIdAndDeletedFalse(userId)).isEmpty();

        var pendingEvents = outboxEventRepository.findAll();
        assertThat(pendingEvents).isNotEmpty();
        assertThat(pendingEvents.stream()
            .filter(e -> response.id().toString().equals(e.getAggregateId().toString()))
            .filter(e -> "order.created.v1".equals(e.getEventType()))
            .findFirst())
            .as("order.created.v1 PENDING outbox row for order %s", response.id())
            .isPresent();
    }

    @Test
    void reservationFailure_releasesNothingAndThrows() {
        UUID productId2 = UUID.randomUUID();
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productId2)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10,"currency":"VND"}}
                """)));
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/.*/reserve"))
            .willReturn(aResponse().withStatus(409)));

        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-2"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
    }

    @Test
    void partialReservationFailure_releasesReservedItems() {
        UUID productA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productA)
            .productTitle("Test A").unitPrice(new BigDecimal("100.00")).quantity(2).build());
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productB)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10,"currency":"VND"}}
                """)));

        UUID item1ReservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productA + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(item1ReservationId, productA))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productB + "/reserve"))
            .willReturn(aResponse().withStatus(409)));
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/reservations/.*/release"))
            .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "partial-fail-key"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        var releaseRequests = inventoryServer.findAll(postRequestedFor(
            urlMatching("/api/v1/inventory/reservations/.*/release")));
        assertThat(releaseRequests).hasSize(1);
        assertThat(releaseRequests.get(0).getUrl())
            .contains(item1ReservationId.toString());

        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
    }

    @Test
    void happyPath_withCoupon_reservesWithRealOrderId_andPersistsReservationId() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test Product","priceUnit":100.00,"currency":"VND"}}
                """.formatted(productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(UUID.randomUUID(), productId))));
        UUID promoReservationId = UUID.randomUUID();
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/SAVE10/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","discountAmount":20.00,"finalAmount":180.00}}
                """.formatted(promoReservationId))));

        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, "SAVE10"), "promo-key-1");

        assertThat(response.subtotal()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(response.discountAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("180.00"));

        var saved = orderRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getPromotionReservationId()).isEqualTo(promoReservationId);
        assertThat(saved.getCouponCode()).isEqualTo("SAVE10");

        var reserveRequests = promotionServer.findAll(
            postRequestedFor(urlEqualTo("/api/v1/promotions/SAVE10/reserve")));
        assertThat(reserveRequests).hasSize(1);
        assertThat(reserveRequests.get(0).getBodyAsString())
            .contains("\"orderId\":\"" + response.id() + "\"")
            .contains("\"userId\":\"" + userId + "\"")
            .contains("\"orderAmount\":200");
    }

    @Test
    void idempotencyReplay_returnsCachedResponse() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test","priceUnit":100,"currency":"VND"}}
                """.formatted(productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(UUID.randomUUID(), productId))));

        OrderResponse first = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        OrderResponse second = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        assertThat(second.id()).isEqualTo(first.id());
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }

    @RepeatedTest(50)
    void productPriceCacheHit_returnsProductSnapshot() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Cached","priceUnit":42.50,"currency":"VND"}}
                """.formatted(productId))));

        var first = productServiceClient.getProduct(productId);
        var second = productServiceClient.getProduct(productId);

        assertThat(first.title()).isEqualTo("Cached");
        assertThat(second).isEqualTo(first);
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }
}
