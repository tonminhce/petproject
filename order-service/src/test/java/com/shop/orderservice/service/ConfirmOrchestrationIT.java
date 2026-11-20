package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfirmOrchestrationIT extends AbstractOrderServiceIT {

    private static final String COUPON = "SAVE10";
    private static final UUID PRODUCT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

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
        Order saved = orderRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getPromotionReservationId()).isEqualTo(promoReservationId);

        stubCapturedPayment(response);
        return response;
    }

    private void stubProduct(UUID productId) {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"T","priceUnit":10,"currency":"VND"}}
                """.formatted(productId))));
    }

    private void stubCapturedPayment(OrderResponse order) {
        paymentServer.stubFor(get(urlEqualTo("/api/v1/payments/by-order/" + order.id() + "/captured"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"orderId":"%s","status":"CAPTURED","id":"%s","amount":%s,"currency":"%s"}}
                """.formatted(order.id(), UUID.randomUUID(), order.total(), order.currency()))));
    }

    private void stubNoCapturedPayment(UUID orderId) {
        paymentServer.stubFor(get(urlEqualTo("/api/v1/payments/by-order/" + orderId + "/captured"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"success":false,"code":"PAY-5002","message":"Payment not found","path":"/api/v1/payments"}
                    """)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder reservationJson(UUID id) {
        return okJson("""
            {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":1}}
            """.formatted(id, id));
    }

    private void stubCommitsAllOk() {        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(200)));
    }


    @Test
    void confirm_withoutCapturedPayment_failsClosedWithOrd4012() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-no-payment");
        stubNoCapturedPayment(order.id());
        stubCommitsAllOk();

        assertThatThrownBy(() -> orderService.confirmOrder(order.id(), adminId.toString(), "no-payment-key"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("ORD-4012"));

        Order stillPending = orderRepository.findById(order.id()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void happyConfirm_promotionCommitsBeforeInventory_orderConfirmed() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-happy");
        seedAdminPrincipal(adminId);

        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(400)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        stubReleaseCommittedSafetyNet();

        String confirmKey = "confirm-happy-key";
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", confirmKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        Order saved = orderRepository.findById(order.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getConfirmedAt()).isNotNull();
        assertThat(idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey))
            .hasValueSatisfying(ik -> {
                assertThat(ik.getResponseStatus()).isEqualTo(200);

                assertThat(ik.getActor()).isEqualTo(adminId.toString());
            });

        List<ServeEvent> promoCommits = posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit");
        List<ServeEvent> invCommits = posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit");
        List<ServeEvent> invCommitsB = posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit");
        assertThat(promoCommits).hasSize(1);
        assertThat(invCommits).hasSize(1);
        assertThat(invCommitsB).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).isEmpty();
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).isEmpty();
    }


    @Test
    void commitFailure_compensatesLifo_staysPending_returns409Ord4011() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-comp");
        seedAdminPrincipal(adminId);

        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(500)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/release-committed"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(400)));
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/release-committed"))
            .willReturn(aResponse().withStatus(200)));

        String confirmKey = "confirm-comp-key";
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", confirmKey))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORD-4011"));

        Order saved = orderRepository.findById(order.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getConfirmedAt()).isNull();
        assertThat(idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey)).isEmpty();

        List<ServeEvent> invReleases = posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed");
        List<ServeEvent> promoReleases = posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed");
        assertThat(invReleases).hasSize(1);
        assertThat(promoReleases).hasSize(1);
        assertThat(invReleases.get(0).getRequest().getLoggedDate())
            .isBeforeOrEqualTo(promoReleases.get(0).getRequest().getLoggedDate());
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release")).isEmpty();
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release")).isEmpty();
    }


    @Test
    void faultInjection_commit500Then200_callerRetryConfirms() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-fault");
        seedAdminPrincipal(adminId);

        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .inScenario("itemB-commit")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("commit-ok"));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .inScenario("itemB-commit")
            .whenScenarioStateIs("commit-ok")
            .willReturn(aResponse().withStatus(200)));
        stubReleaseCommittedSafetyNet();

        String confirmUrl = "/api/v1/orders/" + order.id() + "/confirm";

        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORD-4011"));
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);

        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);

        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();
    }


    @Test
    void timeoutThenReplay_sameKey_secondRunCompletes_rowCompleted() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-replay");
        seedAdminPrincipal(adminId);

        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(500)));
        stubReleaseCommittedSafetyNet();

        String confirmKey = "confirm-replay-key";
        String confirmUrl = "/api/v1/orders/" + order.id() + "/confirm";

        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl).header("Idempotency-Key", confirmKey))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORD-4011"));
        assertThat(idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey)).isEmpty();

        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(200)));

        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl).header("Idempotency-Key", confirmKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        var row = idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey).orElseThrow();
        assertThat(row.getResponseStatus()).isEqualTo(200);
        assertThat(row.getResponseBody()).contains(order.id().toString());

        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();

        assertThat(confirmedEventsFor(order.id())).hasSize(1);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);
    }


    @Test
    void confirm_byServiceToken_idempotencyRowStoresServiceLabelNotSub() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-svc-actor");
        seedServicePrincipal("fulfillment-service", "00000000-0000-0000-0000-00000000f3a1");
        stubCommitsAllOk();
        stubReleaseCommittedSafetyNet();

        String confirmKey = "confirm-svc-actor-key";
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/v1/orders/" + order.id() + "/confirm")
                .header("Idempotency-Key", confirmKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        var row = idempotencyKeyRepository
            .findByActorAndKey("service:fulfillment-service", confirmKey).orElseThrow();
        assertThat(row.getResponseStatus()).isEqualTo(200);
        assertThat(row.getActor()).isEqualTo("service:fulfillment-service");
        assertThat(row.getActor()).isNotEqualTo("00000000-0000-0000-0000-00000000f3a1");
    }


    @Test
    void concurrency_sameKey_exactlyOneOrchestrates_noDoublePublish() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-race-same");
        stubCommitsAllOk();
        stubReleaseCommittedSafetyNet();

        String key = "confirm-race-same-key";
        Callable<Object> sameConfirm = () -> orderService.confirmOrder(order.id(), adminId.toString(), key);
        List<Object> outcomes = race(sameConfirm, sameConfirm);

        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit")).hasSize(1);

        assertThat(outcomes).anySatisfy(o -> {
            if (o instanceof BusinessException be) {
                assertThat(be.getErrorCode()).isEqualTo("ORD-4010");
            } else if (o instanceof OrderResponse r) {
                assertThat(r.status()).isEqualTo(OrderStatus.CONFIRMED);
            } else {
                fail("Unexpected outcome: " + o);
            }
        });

        assertThat(confirmedEventsFor(order.id())).hasSize(1);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);
    }


    @Test
    void concurrency_differentKeys_versionGuardConfirmsExactlyOnce() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-race-diff");
        stubCommitsAllOk();
        stubReleaseCommittedSafetyNet();

        List<Object> outcomes = race(() -> orderService.confirmOrder(order.id(), adminId.toString(), "race-key-1"),
                                     () -> orderService.confirmOrder(order.id(), adminId.toString(), "race-key-2"));

        assertThat(outcomes).anySatisfy(o -> {
            if (o instanceof BusinessException be) {
                assertThat(be.getErrorCode()).isEqualTo("ORD-4004");
            } else if (o instanceof OrderResponse r) {
                assertThat(r.status()).isEqualTo(OrderStatus.CONFIRMED);
            } else {
                assertThat(o).isInstanceOf(OptimisticLockingFailureException.class);
            }
        });

        assertThat(confirmedEventsFor(order.id())).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).isEmpty();
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).isEmpty();
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);

        long promoCommitsBefore = posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit").size();
        assertThatThrownBy(() -> orderService.confirmOrder(order.id(), adminId.toString(), "race-retry-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                be -> assertThat(be.getErrorCode()).isEqualTo("ORD-4004"));
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .hasSize((int) promoCommitsBefore);
    }


    @Test
    void createSaga_taxFailsAfterPromotionReserve_rollsBack_andReleasesPromotionOnce() {
        var cart = cartRepository.save(com.shop.orderservice.entity.Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(PRODUCT_A)
            .productTitle("Product A").unitPrice(new BigDecimal("100.00")).quantity(2).build());

        stubProduct(PRODUCT_A);
        stubProduct(PRODUCT_B);
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/" + COUPON + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","discountAmount":20.00,"finalAmount":230.00}}
                """.formatted(promoReservationId))));
        taxServer.stubFor(post(urlEqualTo("/api/v1/backoffice/tax-rates/calculate"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, COUPON), "create-taxfail-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                be -> assertThat(be.getErrorCode()).isEqualTo("ERR-0503"));

        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
        assertThat(idempotencyKeyRepository.findByActorAndKey(userId.toString(), "create-taxfail-key")).isEmpty();

        assertThat(posted(promotionServer, "/api/v1/promotions/" + COUPON + "/reserve")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).isEmpty();
        assertThat(inventoryServer.getAllServeEvents()).isEmpty();
    }


    private void stubReleaseCommittedSafetyNet() {
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/reservations/[^/]+/release-committed"))
            .willReturn(aResponse().withStatus(200)));
        promotionServer.stubFor(post(urlMatching("/api/v1/promotions/reservations/[^/]+/release-committed"))
            .willReturn(aResponse().withStatus(200)));
    }

    private List<ServeEvent> posted(WireMockServer server, String path) {
        return server.getAllServeEvents().stream()
            .filter(e -> "POST".equals(e.getRequest().getMethod().getName()))
            .filter(e -> e.getRequest().getUrl().equals(path))
            .toList();
    }

    private List<com.shop.orderservice.entity.OutboxEvent> confirmedEventsFor(UUID orderId) {
        return outboxEventRepository.findAll().stream()
            .filter(e -> orderId.equals(e.getAggregateId()))
            .filter(e -> "order.updated.v1".equals(e.getEventType()))
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
