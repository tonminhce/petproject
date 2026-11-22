package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CheckoutGhostOrderCompensationIT extends AbstractOrderServiceIT {

    private static final int RACERS = 20;

    @MockitoBean
    private PricingService pricingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void twentyRacersOnOneCartLeaveZeroGhostOrdersAndZeroOrphanReservationsImmediatelyAfterTheFlow() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Cart cart = cartRepository.save(Cart.builder().userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(CartItem.builder()
            .cartId(cart.getId()).productId(productId)
            .productTitle("Racer Product").unitPrice(new BigDecimal("100.00")).quantity(2).build());

        List<UUID> reservationIds = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            reservationIds.add(UUID.randomUUID());
        }
        stubSequentialReserves(productId, reservationIds);
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/reservations/.*/release"))
            .willReturn(aResponse().withStatus(200)));

        CountDownLatch losersGate = new CountDownLatch(1);
        AtomicBoolean winnerArrived = new AtomicBoolean(false);
        when(pricingService.calculate(any(), eq(userId), anyList(), any())).thenAnswer(inv -> {
            if (winnerArrived.compareAndSet(false, true)) {
                return pricedBreakdown(productId);
            }
            if (!losersGate.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("losers gate timeout");
            }
            return pricedBreakdown(productId);
        });

        UUID winnerId = null;
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            CyclicBarrier barrier = new CyclicBarrier(RACERS);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                String intentKey = "race-intent-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        return (Object) orderService.createOrder(userId,
                            new OrderCreateRequest(null, null), intentKey);
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }

            Order winner = awaitPricedOrder(userId);
            winnerId = winner.getId();
            awaitGhostOrders(userId, RACERS - 1);
            assertThat(bumpGhostVersions(userId)).isEqualTo(RACERS - 1);
            losersGate.countDown();

            int succeeded = 0;
            List<Throwable> failures = new ArrayList<>();
            for (Future<Object> future : futures) {
                Object outcome = future.get(120, TimeUnit.SECONDS);
                if (outcome instanceof OrderResponse response) {
                    succeeded++;
                    assertThat(response.id()).isEqualTo(winnerId);
                } else {
                    assertThat(outcome).isInstanceOf(BusinessException.class);
                    failures.add((Throwable) outcome);
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(failures).hasSize(RACERS - 1);
        } finally {
            losersGate.countDown();
            pool.shutdownNow();
        }

        assertThat(reserveCalls(productId)).isEqualTo(RACERS);
        List<UUID> released = releasedReservations();
        assertThat(released).hasSize(RACERS - 1);
        assertThat(released).containsExactlyInAnyOrderElementsOf(reservationIds.subList(1, RACERS));
        assertThat(released).doesNotContain(reservationIds.get(0));

        List<Order> visible = ordersFor(userId);
        assertThat(visible).hasSize(1);
        Order survivor = visible.get(0);
        assertThat(survivor.getId()).isEqualTo(winnerId);
        assertThat(survivor.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(survivor.getTotal()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(ordersFor(userId).stream()
            .filter(o -> o.getTotal().compareTo(BigDecimal.ZERO) == 0)
            .count())
            .as("ghost orders with total 0.00 must be gone immediately, not after the reconciliation")
            .isZero();

        assertThat(outboxEventsFor(winnerId, "order.created.v1")).hasSize(1);
        List<IdempotencyKey> intentRows = idempotencyKeyRepository.findAll().stream()
            .filter(row -> row.getKey().startsWith("race-intent-"))
            .toList();
        assertThat(intentRows).hasSize(1);
        assertThat(intentRows.get(0).getResponseStatus()).isEqualTo(201);

        stubCapturedPayment(survivor);
        inventoryServer.stubFor(post(urlEqualTo(
            "/api/v1/inventory/reservations/" + reservationIds.get(0) + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        OrderResponse confirmed = orderService.confirmOrder(winnerId,
            userId.toString(), "confirm-race-winner");
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(commitCalls(reservationIds.get(0))).isEqualTo(1);
        assertThat(releasedReservations()).hasSize(RACERS - 1);
    }

    private Order awaitPricedOrder(UUID userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            List<Order> priced = ordersFor(userId).stream()
                .filter(o -> o.getTotal().compareTo(BigDecimal.ZERO) > 0)
                .toList();
            if (!priced.isEmpty()) {
                return priced.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the winning racer never completed checkout");
    }

    private void awaitGhostOrders(UUID userId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            long ghosts = ordersFor(userId).stream()
                .filter(o -> o.getTotal().compareTo(BigDecimal.ZERO) == 0)
                .count();
            if (ghosts >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the losing racers never created their pending orders");
    }

    private int bumpGhostVersions(UUID userId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        AtomicInteger bumped = new AtomicInteger();
        template.executeWithoutResult(status -> orderRepository.findAll().stream()
            .filter(o -> o.getUserId().equals(userId))
            .filter(o -> o.getTotal().compareTo(BigDecimal.ZERO) == 0)
            .forEach(ghost -> {
                ghost.setTrackFailures(ghost.getTrackFailures() == null ? 1 : ghost.getTrackFailures() + 1);
                orderRepository.save(ghost);
                bumped.incrementAndGet();
            }));
        return bumped.get();
    }

    private List<Order> ordersFor(UUID userId) {
        return orderRepository.findAll().stream()
            .filter(o -> o.getUserId().equals(userId))
            .toList();
    }

    private PricingBreakdown pricedBreakdown(UUID productId) {
        return new PricingBreakdown(
            new BigDecimal("200.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("200.00"),
            "VND",
            java.util.Map.of(productId, new ProductSnapshot(productId, "Racer Product",
                new BigDecimal("100.00"), "VND")),
            null);
    }

    private void stubSequentialReserves(UUID productId, List<UUID> ids) {
        String path = "/api/v1/inventory/" + productId + "/reserve";
        String scenario = "ghost-race-" + productId;
        inventoryServer.stubFor(post(urlEqualTo(path))
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(reservationResponse(ids.get(0)))
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

    private void stubCapturedPayment(Order order) {
        paymentServer.stubFor(get(urlEqualTo("/api/v1/payments/by-order/" + order.getId() + "/captured"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"orderId":"%s","status":"CAPTURED","id":"%s","amount":%s,"currency":"%s"}}
                """.formatted(order.getId(), UUID.randomUUID(), order.getTotal(), order.getCurrency()))));
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

    private List<com.shop.orderservice.entity.OutboxEvent> outboxEventsFor(UUID orderId, String eventType) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> orderId.equals(e.getAggregateId()))
            .filter(e -> eventType.equals(e.getEventType()))
            .toList();
    }
}