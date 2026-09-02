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

/**
 * Order-creation saga IT — infrastructure lives in {@link AbstractOrderServiceIT}
 * (factored out for task 13; test bodies unchanged, tax now suite-wide enabled
 * with a zero-rate default stub so totals stay identical).
 */
class OrderCreationSagaIntegrationTest extends AbstractOrderServiceIT {

    private UUID userId;
    private UUID productId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        // Seed cart with 1 item
        var cart = cartRepository.save(Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productId)
            .productTitle("Test Product").unitPrice(new BigDecimal("100.00")).quantity(2).build());
    }

    @Test
    void happyPath_createsOrderAndPublishesEvent() {
        // Wire product-service (wrapped in ApiResponse<ProductSnapshot>)
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test Product","priceUnit":100.00}}
                """.formatted(productId))));

        // Wire inventory-service (wrapped in ApiResponse<ReservationResponse>)
        UUID reservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(reservationId, productId))));

        // Execute
        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-1");

        // Verify
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(orderRepository.findById(response.id())).isPresent();
        assertThat(cartRepository.findByUserIdAndDeletedFalse(userId)).isEmpty();  // cart cleared

        // Outbox assertion — same TX as order insert; must have ≥1 PENDING event for relay.
        var pendingEvents = outboxEventRepository.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 10));
        assertThat(pendingEvents).isNotEmpty();
        // Assert on THIS order's event — the outbox table is shared across the
        // suite and the relay no longer drains leftovers eagerly at context
        // start (C14 initial-delay), so a global get(0) can be another
        // aggregate's row (ProductRatingOutboxIntegrationTest filter precedent).
        assertThat(pendingEvents.stream()
            .filter(e -> response.id().toString().equals(e.getAggregateId().toString()))
            .filter(e -> "order.created.v1".equals(e.getEventType()))
            .findFirst())
            .as("order.created.v1 PENDING outbox row for order %s", response.id())
            .isPresent();
    }

    @Test
    void reservationFailure_releasesNothingAndThrows() {
        // 2 cart items, only 1 reserves successfully — second fails
        UUID productId2 = UUID.randomUUID();
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productId2)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10}}
                """)));
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/.*/reserve"))
            .willReturn(aResponse().withStatus(409)));

        // Execute + verify
        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-2"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        // No Order created for THIS user (TX rollback)
        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
    }

    @Test
    void partialReservationFailure_releasesReservedItems() {
        // 2 cart items — item 1 reserves successfully, item 2 fails. The saga now
        // processes items in productId order (deterministic), so we pin fixed UUIDs:
        // productA sorts first (succeeds), productB second (409).
        // Spec §9 requires the release endpoint to be called for each successful
        // reservation once one fails (compensation invariant).
        UUID productA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        // Replace the random-product item from @BeforeEach with the two fixed ones.
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productA)
            .productTitle("Test A").unitPrice(new BigDecimal("100.00")).quantity(2).build());
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productB)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10}}
                """)));

        UUID item1ReservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productA + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(item1ReservationId, productA))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productB + "/reserve"))
            .willReturn(aResponse().withStatus(409)));
        // The compensation release must reach the inventory API as a real success —
        // the client swallows release failures, so an unstubbed 404 would hide a
        // broken call while the request journal below would still be empty.
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/reservations/.*/release"))
            .willReturn(aResponse().withStatus(200)));

        // Execute + verify saga throws
        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "partial-fail-key"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        // COMPENSATION VERIFIED — the successful reservation was released after the
        // second one failed. With deterministic ordering, exactly one release of
        // item 1's reservation must have been issued.
        var releaseRequests = inventoryServer.findAll(postRequestedFor(
            urlMatching("/api/v1/inventory/reservations/.*/release")));
        assertThat(releaseRequests).hasSize(1);
        assertThat(releaseRequests.get(0).getUrl())
            .contains(item1ReservationId.toString());

        // No Order persisted (TX rollback)
        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
    }

    @Test
    void happyPath_withCoupon_reservesWithRealOrderId_andPersistsReservationId() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test Product","priceUnit":100.00}}
                """.formatted(productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(UUID.randomUUID(), productId))));
        // Task 7 — promotion reserve stub: data carries {reservationId, discountAmount, finalAmount}
        UUID promoReservationId = UUID.randomUUID();
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/SAVE10/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","discountAmount":20.00,"finalAmount":180.00}}
                """.formatted(promoReservationId))));

        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, "SAVE10"), "promo-key-1");

        // subtotal 200, discount 20, tax 0 (suite-wide zero-rate stub), total 180
        assertThat(response.subtotal()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(response.discountAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("180.00"));

        // reservation frozen on the order row (spec D3)
        var saved = orderRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getPromotionReservationId()).isEqualTo(promoReservationId);
        assertThat(saved.getCouponCode()).isEqualTo("SAVE10");

        // persist-early: the reserve call carried the REAL generated orderId
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
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test","priceUnit":100}}
                """.formatted(productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(UUID.randomUUID(), productId))));

        // First call
        OrderResponse first = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        // Second call with same key — should return cached, NOT re-run saga
        OrderResponse second = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        assertThat(second.id()).isEqualTo(first.id());
        // Verify product-service called only ONCE (not twice)
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }

    @RepeatedTest(50)
    void productPriceCacheHit_returnsProductSnapshot() {
        // End-to-end guard for review C1: the productPrice cache entry must come
        // back as a ProductSnapshot record. With the pre-fix serializer (no
        // polymorphic typing) the second call below blew up with a
        // ClassCastException on a LinkedHashMap.
        //
        // productId is FRESH PER ITERATION: @BeforeEach (new test instance per
        // repetition) generates a random UUID, so no cross-iteration key aliasing
        // — a stale entry from iteration N can never turn iteration N+1's first
        // GET into a hit (which would make verify(1) see 0 fetches).
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Cached","priceUnit":42.50}}
                """.formatted(productId))));

        var first = productServiceClient.getProduct(productId);
        var second = productServiceClient.getProduct(productId);  // served from Redis

        assertThat(first.title()).isEqualTo("Cached");
        assertThat(second).isEqualTo(first);
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }
}
