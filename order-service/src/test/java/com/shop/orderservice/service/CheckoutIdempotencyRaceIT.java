package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIdempotencyRaceIT extends AbstractOrderServiceIT {

    private static final int RACERS = 20;

    @Test
    void twentyDistinctRacersOnOneProductProduceExactlyOneOrderPerIntentAndCommitEveryReservation() throws Exception {
        UUID productId = UUID.randomUUID();
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            UUID racer = UUID.randomUUID();
            users.add(racer);
            seedCart(racer, productId);
        }
        List<UUID> reservationIds = reservationIds(RACERS);
        stubProduct(productId);
        stubSequentialReserves(productId, reservationIds, 0);

        List<Object> outcomes = race(RACERS, index ->
            orderService.createOrder(users.get(index),
                new OrderCreateRequest(null, null), "intent-" + index));
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(OrderResponse.class));
        List<OrderResponse> responses = outcomes.stream()
            .map(OrderResponse.class::cast)
            .toList();

        assertThat(responses).hasSize(RACERS);
        assertThat(responses).extracting(OrderResponse::id).doesNotHaveDuplicates();
        assertThat(responses).allSatisfy(response ->
            assertThat(response.total()).isEqualByComparingTo(new BigDecimal("200.00")));
        assertThat(reserveCalls(productId)).isEqualTo(RACERS);

        List<Order> created = ordersFor(users);
        assertThat(created).hasSize(RACERS);
        assertThat(created).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING));
        assertThat(created).noneSatisfy(o ->
            assertThat(o.getTotal()).isEqualByComparingTo(BigDecimal.ZERO));
        List<IdempotencyKey> intentRows = idempotencyKeyRepository.findAll().stream()
            .filter(row -> row.getKey().startsWith("intent-"))
            .toList();
        assertThat(intentRows).hasSize(RACERS);
        assertThat(intentRows).allSatisfy(row -> assertThat(row.getResponseStatus()).isEqualTo(201));

        for (int i = 0; i < RACERS; i++) {
            UUID orderId = responses.get(i).id();
            stubCapturedPayment(orderId, responses.get(i));
            inventoryServer.stubFor(post(urlEqualTo(
                "/api/v1/inventory/reservations/" + reservationIds.get(i) + "/commit"))
                .willReturn(aResponse().withStatus(200)));
            OrderResponse confirmed = orderService.confirmOrder(orderId,
                users.get(i).toString(), "confirm-" + i);
            assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        }

        for (UUID reservationId : reservationIds) {
            assertThat(commitCalls(reservationId)).isEqualTo(1);
        }
        assertThat(releasedReservations()).isEmpty();

        OrderResponse replay = orderService.createOrder(users.get(0),
            new OrderCreateRequest(null, null), "intent-0");
        assertThat(replay.id()).isEqualTo(responses.get(0).id());
        assertThat(reserveCalls(productId)).isEqualTo(RACERS);
        assertThat(ordersFor(users)).hasSize(RACERS);
    }

    @Test
    void concurrentRacersSharingOneIdempotencyIntentCreateExactlyOneOrder() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        seedCart(userId, productId);
        stubProduct(productId);
        stubSequentialReserves(productId, reservationIds(4), 3000);

        List<Object> outcomes = race(4, index ->
            orderService.createOrder(userId, new OrderCreateRequest(null, null), "shared-intent"));

        assertThat(outcomes).hasSize(4);
        assertThat(outcomes.stream().filter(OrderResponse.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
            .map(BusinessException.class::cast))
            .hasSize(3)
            .allSatisfy(ex -> assertThat(ex.getErrorCode())
                .isEqualTo(ErrorCode.ORDER_DUPLICATE_REQUEST.getCode()));

        assertThat(ordersFor(List.of(userId))).hasSize(1);
        assertThat(reserveCalls(productId)).isEqualTo(1);
        assertThat(releasedReservations()).isEmpty();
    }

    @Test
    void checkoutWithoutAnIdempotencyKeyIsRejectedBeforeAnySideEffect() {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        seedCart(userId, productId);
        stubProduct(productId);

        rejectCheckoutWithoutKey(userId, null);
        rejectCheckoutWithoutKey(userId, "   ");

        assertThat(ordersFor(List.of(userId))).isEmpty();
        assertThat(reserveCalls(productId)).isZero();
    }

    private void rejectCheckoutWithoutKey(UUID userId, String key) {
        try {
            orderService.createOrder(userId, new OrderCreateRequest(null, null), key);
            throw new AssertionError("checkout without an idempotency key must be rejected");
        } catch (BusinessException expected) {
            assertThat(expected.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        }
    }

    private List<Order> ordersFor(List<UUID> users) {
        return orderRepository.findAll().stream()
            .filter(o -> users.contains(o.getUserId()))
            .toList();
    }

    private void seedCart(UUID userId, UUID productId) {
        Cart cart = cartRepository.save(Cart.builder().userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productId)
            .productTitle("Racer Product").unitPrice(new BigDecimal("100.00")).quantity(2).build());
    }

    private void stubProduct(UUID productId) {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Racer Product","priceUnit":100.00,"currency":"VND"}}
                """.formatted(productId))));
    }

    private void stubCapturedPayment(UUID orderId, OrderResponse response) {
        paymentServer.stubFor(get(urlEqualTo("/api/v1/payments/by-order/" + orderId + "/captured"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"orderId":"%s","status":"CAPTURED","id":"%s","amount":%s,"currency":"%s"}}
                """.formatted(orderId, UUID.randomUUID(), response.total(), response.currency()))));
    }

    private List<UUID> reservationIds(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private void stubSequentialReserves(UUID productId, List<UUID> ids, int firstCallDelayMillis) {
        String path = "/api/v1/inventory/" + productId + "/reserve";
        String scenario = "reserves-" + productId;
        inventoryServer.stubFor(post(urlEqualTo(path))
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(reservationResponse(ids.get(0)).withFixedDelay(firstCallDelayMillis))
            .willSetStateTo("state-1"));
        for (int i = 1; i < ids.size(); i++) {
            inventoryServer.stubFor(post(urlEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs("state-" + i)
                .willReturn(reservationResponse(ids.get(i)))
                .willSetStateTo("state-" + (i + 1)));
        }
        inventoryServer.stubFor(post(urlEqualTo(path))
            .inScenario(scenario)
            .whenScenarioStateIs("state-" + ids.size())
            .willReturn(aResponse().withStatus(409)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder reservationResponse(UUID id) {
        return okJson("""
            {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":1}}
            """.formatted(id, id));
    }

    private long reserveCalls(UUID productId) {
        return inventoryServer.getAllServeEvents().stream()
            .filter(e -> e.getRequest().getUrl().equals("/api/v1/inventory/" + productId + "/reserve"))
            .count();
    }

    private long commitCalls(UUID reservationId) {
        return inventoryServer.getAllServeEvents().stream()
            .filter(e -> e.getRequest().getUrl()
                .equals("/api/v1/inventory/reservations/" + reservationId + "/commit"))
            .count();
    }

    private List<UUID> releasedReservations() {
        return inventoryServer.getAllServeEvents().stream()
            .filter(e -> e.getRequest().getUrl().endsWith("/release"))
            .map(e -> UUID.fromString(e.getRequest().getUrl().split("/reservations/")[1].split("/")[0]))
            .toList();
    }

    private List<Object> race(int racers, CheckoutAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CyclicBarrier barrier = new CyclicBarrier(racers);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        return (Object) action.checkout(index);
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface CheckoutAction {
        Object checkout(int index) throws Exception;
    }
}