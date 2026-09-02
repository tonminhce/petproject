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

/**
 * Confirm-orchestration IT (hardening §5/§11, task 13) — exercises the REAL
 * stack: {@code OrderStatusController} → {@code confirmOrder} →
 * {@code OrderCommitCoordinator} → WireMock promotion/inventory, plus the two
 * deferred ledger cases (task 11 concurrency, task 7 tax-failure compensation).
 *
 * <p>Coordinator contract under test: promotion commit FIRST, then inventory
 * commits sorted by productId; failure ⇒ LIFO release-committed compensation;
 * the local row stays PENDING until every remote commit succeeds.</p>
 */
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

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** PENDING order with 2 items (productA sorts first) + a frozen promotion reservation. */
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
        return response;
    }

    private void stubProduct(UUID productId) {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"T","priceUnit":10}}
                """.formatted(productId))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder reservationJson(UUID id) {
        return okJson("""
            {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":1}}
            """.formatted(id, id));
    }

    private void stubCommitsAllOk() {
        promotionServer.stubFor(post(urlEqualTo("/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/commit"))
            .willReturn(aResponse().withStatus(200)));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(200)));
    }

    // ------------------------------------------------------------------
    // 1. Happy confirm — promotion commits BEFORE inventory, order CONFIRMED
    // ------------------------------------------------------------------

    @Test
    void happyConfirm_promotionCommitsBeforeInventory_orderConfirmed() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-happy");
        seedAdminPrincipal(adminId);

        // 400ms delay on the promotion commit RESPONSE ⇒ inventory commit receipts are
        // guaranteed ≥400ms after the promotion receipt, so cross-server journal
        // timestamps cannot tie (ms-precision flake guard).
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
                // H-6: ADMIN token → the row owner label is the sub (UUID text), not a parsed column
                assertThat(ik.getActor()).isEqualTo(adminId.toString());
            });

        // Ordering: promotion commit strictly before the first inventory commit
        List<ServeEvent> promoCommits = posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit");
        List<ServeEvent> invCommits = posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit");
        List<ServeEvent> invCommitsB = posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit");
        assertThat(promoCommits).hasSize(1);
        assertThat(invCommits).hasSize(1);
        assertThat(invCommitsB).hasSize(1);
        assertThat(promoCommits.get(0).getRequest().getLoggedDate())
            .isBefore(invCommits.get(0).getRequest().getLoggedDate());
        // Inventory commits in productId order: A then B (single-server journal order)
        assertThat(invCommits.get(0).getRequest().getLoggedDate()).isBefore(invCommitsB.get(0).getRequest().getLoggedDate());
        // Nothing compensated on the happy path
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).isEmpty();
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 2. item-2 commit 500 → LIFO release-committed (item-1 then promotion),
    //    order stays PENDING, 409 ORD-4011
    // ------------------------------------------------------------------

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
        // Compensation targets must be stubbed 200 — clients swallow failures, an
        // unstubbed 404 would hide a broken call while the journal stayed empty.
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resA + "/release-committed"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(400)));  // delay ⇒ promotion receipt strictly later
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
        // abort() removed the in-flight row — the key is free for a retry
        assertThat(idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey)).isEmpty();

        // LIFO compensation: item-1 (last committed) FIRST, then promotion — only
        // release-committed (coordinator), never the saga's plain release.
        List<ServeEvent> invReleases = posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed");
        List<ServeEvent> promoReleases = posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed");
        assertThat(invReleases).hasSize(1);
        assertThat(promoReleases).hasSize(1);
        assertThat(invReleases.get(0).getRequest().getLoggedDate()).isBefore(promoReleases.get(0).getRequest().getLoggedDate());
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release")).isEmpty();
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 3. Fault injection — commit 500 once then 200; caller retry succeeds
    //    (no client-level retry: the SECOND confirm call re-runs and passes)
    // ------------------------------------------------------------------

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

        // First confirm: itemB commit 500 → 409 ORD-4011, order stays PENDING
        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORD-4011"));
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);

        // Caller retry: itemB commit now 200 (idempotent remote branches absorb the
        // double-commit of promotion + item-1) → CONFIRMED
        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);

        // Journal: every commit branch ran twice (aborted run + retry), item-1 and
        // promotion were release-committed exactly once, item-2 never compensated.
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 4. Timeout-then-replay — same Idempotency-Key: aborted run frees the key
    //    (abort), retry re-orchestrates exactly once more, row ends COMPLETED
    // ------------------------------------------------------------------

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

        // Run 1: aborts mid-flight (item-2 500) → 409, key freed
        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl).header("Idempotency-Key", confirmKey))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORD-4011"));
        assertThat(idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey)).isEmpty();

        // "Fix the stubs": a later-registered matching stub wins in WireMock
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/reservations/" + resB + "/commit"))
            .willReturn(aResponse().withStatus(200)));

        // Run 2 with the SAME key: full re-orchestration (previous run was aborted,
        // not completed) → CONFIRMED, row COMPLETED
        mockMvc.perform(MockMvcRequestBuilders.post(confirmUrl).header("Idempotency-Key", confirmKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        var row = idempotencyKeyRepository.findByActorAndKey(adminId.toString(), confirmKey).orElseThrow();
        assertThat(row.getResponseStatus()).isEqualTo(200);
        assertThat(row.getResponseBody()).contains(order.id().toString());

        // Coordinator invoked remotely EXACTLY once per run — no extra invocations
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit")).hasSize(2);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit")).hasSize(2);
        // Compensation only from run 1
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/release-committed")).isEmpty();

        // Exactly one CONFIRMED lifecycle event — never a double publish
        assertThat(confirmedEventsFor(order.id())).hasSize(1);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);
    }

    // ------------------------------------------------------------------
    // 5. H-6 SERVICE-token shape — a machine caller's idempotency row owns
    //    the label "service:<azp>", never the service-account UUID sub
    // ------------------------------------------------------------------

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
        // the misattribution this task kills: the service-account UUID sub must NOT be the owner
        assertThat(row.getActor()).isEqualTo("service:fulfillment-service");
        assertThat(row.getActor()).isNotEqualTo("00000000-0000-0000-0000-00000000f3a1");
    }

    // ------------------------------------------------------------------
    // 5a. CONCURRENCY (deferred from task 11) — same key, two threads:
    //     exactly ONE orchestrates, the other is rejected/replayed
    // ------------------------------------------------------------------

    @Test
    void concurrency_sameKey_exactlyOneOrchestrates_noDoublePublish() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-race-same");
        stubCommitsAllOk();
        stubReleaseCommittedSafetyNet();

        String key = "confirm-race-same-key";
        Callable<Object> sameConfirm = () -> orderService.confirmOrder(order.id(), adminId.toString(), key);
        List<Object> outcomes = race(sameConfirm, sameConfirm);

        // Exactly one orchestrator: every remote commit branch hit exactly once
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/commit")).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resB + "/commit")).hasSize(1);

        // Winner-vs-loser responses are deliberately NOT counted: a loser whose
        // begin() lands after the winner's complete() legitimately replays the
        // cached 200 (spec §3.7) — a CONFIRMED OrderResponse indistinguishable
        // from the winner's — so a winners==N gate would flake on loaded CI.
        // The sound teeth for "exactly ONE orchestrates" are the journal ×1
        // asserts above, plus the single CONFIRMED event below. Expected loser
        // outcomes: ORD-4010 (began mid-flight) or the cached-replay 200.
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

    // ------------------------------------------------------------------
    // 5b. CONCURRENCY (deferred from task 11) — different keys: both may
    //     orchestrate remotely (idempotent branches absorb it), but the
    //     @Version guard lets only ONE flip the local row ⇒ exactly one
    //     CONFIRMED event; the loser's retry surfaces the clean state guard.
    //     Winner-vs-loser responses are deliberately NOT counted: a loser
    //     whose begin() lands after the winner's complete() legitimately
    //     replays the cached 200 (spec §3.7) — a CONFIRMED OrderResponse
    //     indistinguishable from the winner's — so a winners==N gate would
    //     flake on loaded CI. The sound teeth are the exact invariants
    //     below: no compensation churn, exactly one CONFIRMED event, and a
    //     journal-unchanged guard on the follow-up confirm.
    // ------------------------------------------------------------------

    @Test
    void concurrency_differentKeys_versionGuardConfirmsExactlyOnce() throws Exception {
        OrderResponse order = pendingOrderWithPromotion("create-race-diff");
        stubCommitsAllOk();
        stubReleaseCommittedSafetyNet();

        List<Object> outcomes = race(() -> orderService.confirmOrder(order.id(), adminId.toString(), "race-key-1"),
                                     () -> orderService.confirmOrder(order.id(), adminId.toString(), "race-key-2"));

        // Expected loser outcomes (all spec §3.7-safe): ORD-4004 clean-state
        // guard (loaded after the winner committed), an @Version
        // OptimisticLockingFailureException at TX commit, or the cached-replay
        // 200 (begin() after the winner's complete() — a CONFIRMED
        // OrderResponse, which is exactly why winners must not be counted).
        // ORD-4010 is impossible here (keys differ ⇒ begin() never sees a
        // foreign in-flight row); ORD-4011 is impossible (stubCommitsAllOk()
        // never fails the coordinator).
        assertThat(outcomes).anySatisfy(o -> {
            if (o instanceof BusinessException be) {
                assertThat(be.getErrorCode()).isEqualTo("ORD-4004");
            } else if (o instanceof OrderResponse r) {
                assertThat(r.status()).isEqualTo(OrderStatus.CONFIRMED);
            } else {
                assertThat(o).isInstanceOf(OptimisticLockingFailureException.class);
            }
        });

        // No double publish, no compensation churn, order ends CONFIRMED exactly once
        assertThat(confirmedEventsFor(order.id())).hasSize(1);
        assertThat(posted(inventoryServer, "/api/v1/inventory/reservations/" + resA + "/release-committed")).isEmpty();
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).isEmpty();
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);

        // Follow-up confirm: state guard rejects CONFIRMED→CONFIRMED and the
        // coordinator is NOT re-invoked remotely (journal unchanged).
        long promoCommitsBefore = posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit").size();
        assertThatThrownBy(() -> orderService.confirmOrder(order.id(), adminId.toString(), "race-retry-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                be -> assertThat(be.getErrorCode()).isEqualTo("ORD-4004"));
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/commit"))
            .hasSize((int) promoCommitsBefore);
    }

    // ------------------------------------------------------------------
    // 6. T7 ledger — coupon-reserve 200 + tax 500: create fails, TX rollback,
    //    promotion plain RELEASE once, no inventory release-committed
    // ------------------------------------------------------------------

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
        // Base @BeforeEach registered a zero-rate tax stub; the LATER failure stub wins.
        taxServer.stubFor(post(urlEqualTo("/api/v1/backoffice/tax-rates/calculate"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, COUPON), "create-taxfail-key"))
            .isInstanceOfSatisfying(BusinessException.class,
                be -> assertThat(be.getErrorCode()).isEqualTo("ERR-0503"));  // tax 5xx fails closed

        // TX rollback — no order row for this user, idempotency row aborted away
        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
        assertThat(idempotencyKeyRepository.findByActorAndKey(userId.toString(), "create-taxfail-key")).isEmpty();

        // Promotion reservation was created remotely, so it must be RELEASED
        // (best-effort, plain /release — the saga's compensation convention; the
        // commit-side /release-committed belongs to the CONFIRM coordinator only).
        assertThat(posted(promotionServer, "/api/v1/promotions/" + COUPON + "/reserve")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release")).hasSize(1);
        assertThat(posted(promotionServer, "/api/v1/promotions/reservations/" + promoReservationId + "/release-committed")).isEmpty();
        // Tax failed BEFORE the reserve loop — inventory untouched entirely
        assertThat(inventoryServer.getAllServeEvents()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void stubReleaseCommittedSafetyNet() {
        // Clients swallow release failures — stub 200 so a broken call can't hide
        // behind a 404 while the journal assertions below stay exact.
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

    /** Runs the given confirms on parallel threads released by a shared barrier. */
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
                        return ex;  // exceptions ARE a result here (loser outcomes)
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
