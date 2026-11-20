package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.config.CompensationProperties;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.FailedCompensation;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.repository.FailedCompensationRepository;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfirmCompensationLedgerIT extends AbstractOrderServiceIT {

    private static final String COUPON = "SAVE10";
    private static final UUID PRODUCT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Autowired
    private CompensationFailureService compensationFailureService;
    @Autowired
    private FailedCompensationRepository failedCompensationRepository;
    @Autowired
    private StockReservationService stockReservationService;
    @Autowired
    private PromotionServiceClient promotionClient;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private CompensationProperties compensationProperties;

    @Value("${order.compensation.max-attempts:5}")
    private int maxAttempts;

    private UUID userId;
    private UUID adminId;
    private UUID resA;
    private UUID resB;
    private UUID promoReservationId;

    @BeforeEach
    void seed() {
        userId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        resA = UUID.randomUUID();
        resB = UUID.randomUUID();
        promoReservationId = UUID.randomUUID();
        failedCompensationRepository.deleteAll();
    }

    @Test
    void aFailedStockReleaseInTheConfirmCompensationIsRecordedInTheLedger() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-ledger");
        stubCommitsWithAFailingItem();
        stubFailingInventoryReleaseCommitted();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", "confirm-ledger-key"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORD-4011"));

        List<FailedCompensation> rows = ledgerRows(resA);
        assertThat(rows).hasSize(1);
        FailedCompensation row = rows.get(0);
        assertThat(row.getKind()).isEqualTo(FailedCompensation.KIND_RESERVATION_RELEASE_COMMITTED);
        assertThat(row.getStatus()).isEqualTo(FailedCompensation.STATUS_PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getOrderId()).isEqualTo(order.id());
        assertThat(row.getNextAttemptAt()).isNotNull();
        assertThat(row.getLastError()).isNotBlank();

        Order orderRow = orderRepository.findById(order.id()).orElseThrow();
        assertThat(orderRow.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderRow.getConfirmedAt()).isNull();

        assertThat(orderItemRepository.findByOrderId(order.id()))
            .isNotEmpty()
            .allSatisfy(item -> assertThat(item.getReservationId()).isNotNull());

        assertThat(eventsFor(order.id(), "order.created.v1")).hasSize(1);
        assertThat(eventsFor(order.id(), "order.updated.v1")).isEmpty();
    }

    @Test
    void theSweeperReleasesTheStockOnALaterPass() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-sweeper");
        stubCommitsWithAFailingItem();
        stubFailingInventoryReleaseCommitted();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", "confirm-sweeper-key"))
            .andExpect(status().isConflict());

        assertThat(ledgerRows(resA)).hasSize(1);
        int releasesBefore = posted(inventoryServer, releaseCommittedPath(resA)).size();
        assertThat(releasesBefore).isEqualTo(1);

        inventoryServer.stubFor(post(urlEqualTo(releaseCommittedPath(resA)))
            .willReturn(aResponse().withStatus(200)));

        compensationFailureService.retryPending();

        assertThat(posted(inventoryServer, releaseCommittedPath(resA))).hasSize(releasesBefore + 1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release")).isEmpty();

        FailedCompensation row = ledgerRows(resA).get(0);
        assertThat(row.getStatus()).isEqualTo(FailedCompensation.STATUS_RESOLVED);
        assertThat(row.getResolvedAt()).isNotNull();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    @Test
    void theSweeperStopsAfterTheBoundedAttemptCount() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-bound");
        stubCommitsWithAFailingItem();
        stubFailingInventoryReleaseCommitted();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", "confirm-bound-key"))
            .andExpect(status().isConflict());

        double exhaustedBefore = exhaustedCounter().count();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            makeDue(resA);
            compensationFailureService.retryPending();
        }

        FailedCompensation exhausted = ledgerRows(resA).get(0);
        assertThat(exhausted.getStatus()).isEqualTo(FailedCompensation.STATUS_EXHAUSTED);
        assertThat(exhausted.getAttempts()).isEqualTo(maxAttempts);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(exhaustedCounter().count()).isEqualTo(exhaustedBefore + 1);

        int releasesAtExhaustion = posted(inventoryServer, releaseCommittedPath(resA)).size();
        makeDue(resA);
        compensationFailureService.retryPending();

        assertThat(posted(inventoryServer, releaseCommittedPath(resA))).hasSize(releasesAtExhaustion);
        assertThat(ledgerRows(resA).get(0).getStatus()).isEqualTo(FailedCompensation.STATUS_EXHAUSTED);
    }

    @Test
    void twoConcurrentSweepersReleaseTheStockExactlyOnce() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-race");
        stubCommitsWithAFailingItem();
        stubFailingInventoryReleaseCommitted();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", "confirm-race-key"))
            .andExpect(status().isConflict());

        inventoryServer.stubFor(post(urlEqualTo(releaseCommittedPath(resA)))
            .willReturn(aResponse().withStatus(200).withFixedDelay(300)));

        int releasesBefore = posted(inventoryServer, releaseCommittedPath(resA)).size();
        CompensationFailureService secondReplica = new CompensationFailureService(
            failedCompensationRepository, stockReservationService, promotionClient,
            transactionManager, meterRegistry, compensationProperties);

        race(compensationFailureService::retryPending, secondReplica::retryPending);

        assertThat(posted(inventoryServer, releaseCommittedPath(resA))).hasSize(releasesBefore + 1);
        assertThat(ledgerRows(resA).get(0).getStatus()).isEqualTo(FailedCompensation.STATUS_RESOLVED);
    }

    private Counter exhaustedCounter() {
        return meterRegistry.counter("order.compensation.exhausted",
            "kind", FailedCompensation.KIND_RESERVATION_RELEASE_COMMITTED);
    }

    private List<FailedCompensation> ledgerRows(UUID reservationId) {
        return failedCompensationRepository.findAll().stream()
                .filter(row -> reservationId.equals(row.getReservationId()))
                .toList();
    }

    private void makeDue(UUID reservationId) {
        FailedCompensation row = ledgerRows(reservationId).get(0);
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        failedCompensationRepository.save(row);
    }

    private static String releaseCommittedPath(UUID reservationId) {
        return "/api/v1/inventory/reservations/" + reservationId + "/release-committed";
    }

    private OrderResponse pendingOrderWithPromotion(String createKey) {
        var cart = cartRepository.save(com.shop.orderservice.entity.Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(PRODUCT_A)
            .productTitle("Product A").unitPrice(new BigDecimal("100.00")).quantity(2).build());
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(PRODUCT_B)
            .productTitle("Product B").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        stubProduct(PRODUCT_A);
        stubProduct(PRODUCT_B);
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + PRODUCT_A + "/reserve"))
            .willReturn(reservationJson(resA)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + PRODUCT_B + "/reserve"))
            .willReturn(reservationJson(resB)));
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/" + COUPON + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","discountAmount":20.00,"finalAmount":230.00}}
                """.formatted(promoReservationId))));

        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, COUPON), createKey);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);

        paymentServer.stubFor(get(urlEqualTo("/api/v1/payments/by-order/" + response.id() + "/captured"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"orderId":"%s","status":"CAPTURED","id":"%s","amount":%s,"currency":"%s"}}
                """.formatted(response.id(), UUID.randomUUID(), response.total(), response.currency()))));
        return response;
    }

    private void stubProduct(UUID productId) {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"T","priceUnit":10,"currency":"VND"}}
                """.formatted(productId))));
    }

    private void stubCommitsWithAFailingItem() {
        seedAdminPrincipal(adminId);
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(500)));
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/release-committed"))
            .willReturn(aResponse().withStatus(200)));
    }

    private void stubFailingInventoryReleaseCommitted() {
        inventoryServer.stubFor(post(urlEqualTo(releaseCommittedPath(resA)))
            .willReturn(aResponse().withStatus(500)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder reservationJson(UUID id) {
        return okJson("""
            {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":1}}
            """.formatted(id, id));
    }

    private List<ServeEvent> posted(WireMockServer server, String path) {
        return server.getAllServeEvents().stream()
            .filter(e -> "POST".equals(e.getRequest().getMethod().getName()))
            .filter(e -> e.getRequest().getUrl().equals(path))
            .toList();
    }

    private List<OutboxEvent> eventsFor(UUID orderId, String eventType) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> orderId.equals(e.getAggregateId()))
            .filter(e -> eventType.equals(e.getEventType()))
            .toList();
    }

    private List<Object> race(Callable<Object>... actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        try {
            CyclicBarrier barrier = new CyclicBarrier(actions.length);
            List<Future<Object>> futures = java.util.Arrays.stream(actions)
                .map(action -> pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return action.call();
                    } catch (Exception ex) {
                        return ex;
                    }
                }))
                .toList();
            return futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    return ex;
                }
            }).toList();
        } finally {
            pool.shutdownNow();
        }
    }
}
