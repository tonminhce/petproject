package com.shop.promotionservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import com.shop.promotionservice.service.ReservationRetryService;
import com.shop.promotionservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13 — reserve-flow integration suite: the real
 * {@link ReservationRetryService} → {@code CampaignReservationServiceImpl}
 * call path (the controller's production entry point) against real Postgres
 * (Liquibase-owned schema, singleton containers from the harness) and the
 * real transactional outbox.
 *
 * <p>Covers spec §5.1: happy path persistence contract (frozen amounts,
 * TTL-derived {@code expiresAt}, reserved outbox row), every PRO-7xxx gate
 * branch seeded through the real repositories, the optimistic-lock
 * version-touch race under 8 concurrent threads (§5.2), and budget
 * quota-return when a hold expires (§5.4 semantics, exercised by directly
 * flipping the row to EXPIRED — the sweep itself is covered by its own unit
 * tests and the lifecycle suite).</p>
 */
class ReserveFlowIT extends AbstractIntegrationTest {

    private static final long TTL_SECONDS = 900L;
    /** PRO-7006 / PRO-7011 — the only failure codes permitted for losing threads. */
    private static final Set<String> RACE_FAILURE_CODES = Set.of("PRO-7006", "PRO-7011");
    private static final List<UsageStatus> COUNTED_STATUSES =
        List.of(UsageStatus.PENDING, UsageStatus.COMMITTED);

    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponUsageReservationRepository reservationRepository;
    @Autowired
    private ReservationRetryService reservationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- helpers -----------------------------------------------------------

    private Campaign.CampaignBuilder baseCampaign(String code) {
        Instant now = Instant.now();
        return Campaign.builder()
            .code(code)
            .name("IT campaign " + code)
            .discountType(DiscountCalculator.TYPE_PERCENT)
            .discountValue(new BigDecimal("10.00"))
            .startsAt(now.minusSeconds(3600))
            .endsAt(now.plusSeconds(86400))
            // Unlimited per-user by default: individual gate tests opt in.
            .perUserLimit(0)
            .status(CampaignStatus.ACTIVE);
    }

    private Campaign seedCampaign(Campaign campaign) {
        return campaignRepository.save(campaign);
    }

    private ReserveRequest request(UUID userId, String orderAmount) {
        return new ReserveRequest(userId, UUID.randomUUID(), new BigDecimal(orderAmount));
    }

    /** Direct repo seed of a usage row (real persistence path, no service gates). */
    private CouponUsageReservation seedReservation(UUID campaignId, UUID userId, UsageStatus status,
                                                   String discount, Instant reservedAt) {
        return reservationRepository.save(CouponUsageReservation.builder()
            .campaignId(campaignId)
            .userId(userId)
            .orderId(UUID.randomUUID())
            .orderAmount(new BigDecimal("500.00"))
            .discountAmount(new BigDecimal(discount))
            .status(status)
            .reservedAt(reservedAt)
            .expiresAt(reservedAt.plusSeconds(TTL_SECONDS))
            .build());
    }

    private List<String> outboxPayloads(String eventType) {
        return jdbcTemplate.queryForList(
            "select payload from outbox_events where event_type = ? order by id",
            String.class, eventType);
    }

    // --- 1. happy reserve ---------------------------------------------------

    @Test
    @DisplayName("happy reserve → PENDING row with frozen amounts + expiresAt=reservedAt+900 + reserved outbox row")
    void happyReservePersistsPendingRowAndOutboxEvent() {
        Campaign campaign = seedCampaign(baseCampaign("HAPPY-IT")
            .discountValue(new BigDecimal("10.00"))
            .build());
        UUID userId = UUID.randomUUID();

        ReservationResponse response =
            reservationService.reserveWithRetry("HAPPY-IT", request(userId, "199.99"));

        // 201-equivalent: no exception, response carries the frozen math
        // (199.99 × 10% → 20.00 HALF_UP).
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(response.finalAmount()).isEqualByComparingTo("179.99");

        // Real DB row: PENDING, frozen discount, TTL-derived expiry.
        CouponUsageReservation row = reservationRepository.findById(response.reservationId())
            .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(UsageStatus.PENDING);
        assertThat(row.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(row.getOrderAmount()).isEqualByComparingTo("199.99");
        assertThat(Duration.between(row.getReservedAt(), row.getExpiresAt()).getSeconds())
            .isEqualTo(TTL_SECONDS);

        // Same-transaction outbox row for promotion.reserved.v1.
        assertThat(outboxPayloads("promotion.reserved.v1"))
            .anySatisfy(payload -> {
                assertThat(payload).contains(response.reservationId().toString());
                assertThat(payload).contains("\"code\":\"HAPPY-IT\"");
                assertThat(payload).contains("\"discountAmount\":20.00");
            });
        List<String> statuses = jdbcTemplate.queryForList(
            "select status from outbox_events where event_type = 'promotion.reserved.v1' " +
            "and payload like ?",
            String.class, "%" + response.reservationId() + "%");
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0)).isEqualTo("PENDING");
    }

    // --- 2. gate branches (PRO-7xxx), seeded via real repos ------------------

    @Test
    @DisplayName("unknown code → CAMPAIGN_NOT_FOUND (PRO-7001)")
    void unknownCodeRejected7001() {
        assertThatThrownBy(() -> reservationService.reserveWithRetry("GHOST-CODE-IT",
                request(UUID.randomUUID(), "199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7001"));
    }

    @Test
    @DisplayName("INACTIVE campaign → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void inactiveCampaignRejected7004() {
        seedCampaign(baseCampaign("INACTIVE-IT").status(CampaignStatus.INACTIVE).build());

        assertThatThrownBy(() -> reservationService.reserveWithRetry("INACTIVE-IT",
                request(UUID.randomUUID(), "199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }

    @Test
    @DisplayName("reserve before starts_at → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void campaignNotYetStartedRejected7004() {
        seedCampaign(baseCampaign("NOTSTARTED-IT")
            .startsAt(Instant.now().plusSeconds(3600))
            .build());

        assertThatThrownBy(() -> reservationService.reserveWithRetry("NOTSTARTED-IT",
                request(UUID.randomUUID(), "199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }

    @Test
    @DisplayName("reserve after ends_at → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void campaignAlreadyEndedRejected7004() {
        seedCampaign(baseCampaign("ENDED-IT")
            .endsAt(Instant.now().minusSeconds(3600))
            .build());

        assertThatThrownBy(() -> reservationService.reserveWithRetry("ENDED-IT",
                request(UUID.randomUUID(), "199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }

    @Test
    @DisplayName("order below min_order_amount → MIN_ORDER_AMOUNT_NOT_MET (PRO-7005)")
    void belowMinOrderRejected7005() {
        seedCampaign(baseCampaign("MINORDER-IT")
            .minOrderAmount(new BigDecimal("100.00"))
            .build());

        assertThatThrownBy(() -> reservationService.reserveWithRetry("MINORDER-IT",
                request(UUID.randomUUID(), "50.00")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7005"));
    }

    @Test
    @DisplayName("per_user_limit reached (2 seeded COMMITTED, limit 2) → PER_USER_LIMIT_EXCEEDED (PRO-7006)")
    void perUserLimitExceededRejected7006() {
        Campaign campaign = seedCampaign(baseCampaign("PERUSER-IT").perUserLimit(2).build());
        UUID userId = UUID.randomUUID();
        Instant yesterday = Instant.now().minusSeconds(86_400);
        seedReservation(campaign.getId(), userId, UsageStatus.COMMITTED, "20.00", yesterday);
        seedReservation(campaign.getId(), userId, UsageStatus.COMMITTED, "20.00", yesterday);

        assertThatThrownBy(() -> reservationService.reserveWithRetry("PERUSER-IT",
                request(userId, "199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7006"));
    }

    @Test
    @DisplayName("max_redemptions reached (1 seeded COMMITTED, max 1) → BUDGET_EXHAUSTED (PRO-7007)")
    void maxRedemptionsExceededRejected7007() {
        Campaign campaign = seedCampaign(baseCampaign("MAXRED-IT")
            .maxRedemptions(1)
            .build());
        seedReservation(campaign.getId(), UUID.randomUUID(), UsageStatus.COMMITTED,
            "20.00", Instant.now().minusSeconds(86_400));

        assertThatThrownBy(() -> reservationService.reserveWithRetry("MAXRED-IT",
                request(UUID.randomUUID(), "199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7007"));
    }

    @Test
    @DisplayName("budget exhausted (seeded PENDING 90 of budget 100, new discount 50) → BUDGET_EXHAUSTED (PRO-7007)")
    void budgetExhaustedRejected7007() {
        Campaign campaign = seedCampaign(baseCampaign("BUDGETGATE-IT")
            .discountValue(new BigDecimal("50.00"))
            .totalBudget(new BigDecimal("100.00"))
            .build());
        seedReservation(campaign.getId(), UUID.randomUUID(), UsageStatus.PENDING,
            "90.00", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> reservationService.reserveWithRetry("BUDGETGATE-IT",
                request(UUID.randomUUID(), "100.00")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7007"));
    }

    // --- 3. concurrency: version-touch + optimistic retry vs real Postgres ---

    @Test
    @DisplayName("8 concurrent reserves, per_user_limit=3 → exactly 3 succeed, 5 rejected PRO-7006/7011, DB invariant holds")
    void concurrentReservesEnforcePerUserLimitUnderRealPostgres() throws Exception {
        Campaign campaign = seedCampaign(baseCampaign("RACE-IT").perUserLimit(3).build());
        UUID userId = UUID.randomUUID();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<ReservationResponse> successes = new CopyOnWriteArrayList<>();
        List<String> failureCodes = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    try {
                        // Maximize collision: all 8 read the campaign before any
                        // version-touch flush lands, forcing the loser of each
                        // round through OptimisticLockingFailureException → the
                        // retry wrapper's re-read + re-validate.
                        barrier.await(15, TimeUnit.SECONDS);
                        successes.add(reservationService.reserveWithRetry(
                            "RACE-IT", request(userId, "199.99")));
                    } catch (BusinessException ex) {
                        failureCodes.add(ex.getErrorCode());
                    } finally {
                        done.countDown();
                    }
                    return null;
                }));
            }
            assertThat(done.await(60, TimeUnit.SECONDS))
                .as("all 8 reserve attempts must finish").isTrue();
            for (Future<?> future : futures) {
                future.get(1, TimeUnit.SECONDS); // propagate unexpected errors
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes).hasSize(3);
        assertThat(successes).extracting(ReservationResponse::reservationId).doesNotHaveDuplicates();
        assertThat(failureCodes).hasSize(5);
        assertThat(failureCodes).allMatch(RACE_FAILURE_CODES::contains,
            "expected only PRO-7006/PRO-7011 but got " + failureCodes);

        // DB invariant — quota accounting counts PENDING + COMMITTED holds.
        assertThat(reservationRepository
            .countByCampaignIdAndUserIdAndStatusIn(campaign.getId(), userId, COUNTED_STATUSES))
            .isEqualTo(3L);
        assertThat(reservationRepository
            .countByCampaignIdAndStatusIn(campaign.getId(), COUNTED_STATUSES))
            .isEqualTo(3L);
    }

    // --- 4. budget quota-return on expiry ------------------------------------

    @Test
    @DisplayName("budget: 60 of 100 reserved → second reserve PRO-7007 → hold EXPIRED → reserve OK again (quota returned)")
    void budgetQuotaReturnedWhenReservationExpires() {
        Campaign campaign = seedCampaign(baseCampaign("BUDGETRETURN-IT")
            .discountType(DiscountCalculator.TYPE_FIXED)
            .discountValue(new BigDecimal("60.00"))
            .totalBudget(new BigDecimal("100.00"))
            .build());
        UUID firstUser = UUID.randomUUID();

        // Fill 60 of 100.
        ReservationResponse first =
            reservationService.reserveWithRetry("BUDGETRETURN-IT", request(firstUser, "500.00"));
        assertThat(first.discountAmount()).isEqualByComparingTo("60.00");

        // 60 + 60 > 100 → PRO-7007.
        assertThatThrownBy(() -> reservationService.reserveWithRetry("BUDGETRETURN-IT",
                request(UUID.randomUUID(), "500.00")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7007"));

        // Simulate the TTL sweep (deterministic — no reliance on the 60s timer):
        // flip the first hold to EXPIRED directly through the real repository.
        CouponUsageReservation expired = reservationRepository.findById(first.reservationId())
            .orElseThrow();
        expired.setStatus(UsageStatus.EXPIRED);
        reservationRepository.save(expired);

        // Quota returned: the same 60 discount fits again.
        ReservationResponse second =
            reservationService.reserveWithRetry("BUDGETRETURN-IT", request(UUID.randomUUID(), "500.00"));
        assertThat(second.status()).isEqualTo("PENDING");
        assertThat(second.discountAmount()).isEqualByComparingTo("60.00");

        List<CouponUsageReservation> rows =
            reservationRepository.findAll().stream()
                .filter(r -> r.getCampaignId().equals(campaign.getId()))
                .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CouponUsageReservation::getStatus)
            .containsExactlyInAnyOrder(UsageStatus.EXPIRED, UsageStatus.PENDING);
    }
}
