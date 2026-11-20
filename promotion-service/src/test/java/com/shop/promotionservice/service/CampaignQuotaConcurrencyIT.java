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
import com.shop.promotionservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

class CampaignQuotaConcurrencyIT extends AbstractIntegrationTest {

    private static final long TTL_SECONDS = 900L;

    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponUsageReservationRepository reservationRepository;
    @Autowired
    private ReservationRetryService reservationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Campaign seedCampaign(String code, int perUserLimit, Integer maxRedemptions,
                                  BigDecimal totalBudget) {
        Instant now = Instant.now();
        return campaignRepository.save(Campaign.builder()
            .code(code)
            .name("Quota campaign " + code)
            .discountType(DiscountCalculator.TYPE_PERCENT)
            .discountValue(new BigDecimal("10.00"))
            .startsAt(now.minusSeconds(3600))
            .endsAt(now.plusSeconds(86400))
            .perUserLimit(perUserLimit)
            .maxRedemptions(maxRedemptions)
            .totalBudget(totalBudget)
            .status(CampaignStatus.ACTIVE)
            .build());
    }

    private ReserveRequest request(UUID userId, String orderAmount) {
        return new ReserveRequest(userId, UUID.randomUUID(), new BigDecimal(orderAmount), "VND");
    }

    private int usedCount(UUID campaignId) {
        return jdbcTemplate.queryForObject(
            "select used_count from campaign where id = ?", Integer.class, campaignId);
    }

    private int userUsedCount(UUID campaignId, UUID userId) {
        return jdbcTemplate.queryForObject(
            "select used_count from campaign_user_usage where campaign_id = ? and user_id = ?",
            Integer.class, campaignId, userId);
    }

    private List<CouponUsageReservation> reservationsOf(UUID campaignId) {
        return reservationRepository.findAll().stream()
            .filter(r -> r.getCampaignId().equals(campaignId))
            .toList();
    }

    @Test
    @DisplayName("a coupon is redeemed up to its usage limit and the counters track the redemptions")
    void couponIsRedeemedUpToItsUsageLimit() {
        Campaign campaign = seedCampaign("QUOTA-HAPPY-IT", 2, null, null);
        UUID userId = UUID.randomUUID();

        ReservationResponse first = reservationService.reserveWithRetry("QUOTA-HAPPY-IT",
            request(userId, "199.99"));
        ReservationResponse second = reservationService.reserveWithRetry("QUOTA-HAPPY-IT",
            request(userId, "199.99"));

        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(second.status()).isEqualTo("PENDING");
        assertThat(first.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(second.discountAmount()).isEqualByComparingTo("20.00");

        List<CouponUsageReservation> rows = reservationsOf(campaign.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(UsageStatus.PENDING);
            assertThat(row.getUserId()).isEqualTo(userId);
            assertThat(row.getDiscountAmount()).isEqualByComparingTo("20.00");
        });
        assertThat(usedCount(campaign.getId())).isEqualTo(2);
        assertThat(userUsedCount(campaign.getId(), userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a coupon at its usage limit is rejected and no reservation row is written")
    void couponAtItsUsageLimitIsRejected() {
        Campaign campaign = seedCampaign("QUOTA-LIMIT-IT", 1, null, null);
        UUID userId = UUID.randomUUID();

        reservationService.reserveWithRetry("QUOTA-LIMIT-IT", request(userId, "199.99"));

        assertThatThrownBy(() -> reservationService.reserveWithRetry("QUOTA-LIMIT-IT",
            request(userId, "199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7006"));

        List<CouponUsageReservation> rows = reservationsOf(campaign.getId());
        assertThat(rows).hasSize(1);
        assertThat(usedCount(campaign.getId())).isEqualTo(1);
        assertThat(userUsedCount(campaign.getId(), userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a campaign at max_redemptions is rejected and no reservation row is written")
    void campaignAtMaxRedemptionsIsRejected() {
        Campaign campaign = seedCampaign("QUOTA-MAX-IT", 0, 1, null);

        reservationService.reserveWithRetry("QUOTA-MAX-IT", request(UUID.randomUUID(), "199.99"));

        assertThatThrownBy(() -> reservationService.reserveWithRetry("QUOTA-MAX-IT",
            request(UUID.randomUUID(), "199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7007"));

        assertThat(reservationsOf(campaign.getId())).hasSize(1);
        assertThat(usedCount(campaign.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("two threads redeem the last remaining coupon use concurrently: exactly one succeeds")
    void twoThreadsRedeemingTheLastUseYieldExactlyOneSuccess() throws Exception {
        Campaign campaign = seedCampaign("QUOTA-RACE-IT", 1, null, null);
        UUID userId = UUID.randomUUID();
        int threads = 2;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<ReservationResponse> successes = new CopyOnWriteArrayList<>();
        List<String> failureCodes = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    try {
                        barrier.await(15, TimeUnit.SECONDS);
                        successes.add(reservationService.reserveWithRetry(
                            "QUOTA-RACE-IT", request(userId, "199.99")));
                    } catch (BusinessException ex) {
                        failureCodes.add(ex.getErrorCode());
                    } finally {
                        done.countDown();
                    }
                    return null;
                }));
            }
            assertThat(done.await(60, TimeUnit.SECONDS))
                .as("both redeem attempts must finish").isTrue();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes).hasSize(1);
        assertThat(failureCodes).hasSize(1);
        assertThat(failureCodes).allMatch(code -> code.equals("PRO-7006"),
            "expected PRO-7006 but got " + failureCodes);

        List<CouponUsageReservation> rows = reservationsOf(campaign.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(UsageStatus.PENDING);
        assertThat(usedCount(campaign.getId())).isEqualTo(1);
        assertThat(userUsedCount(campaign.getId(), userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the promotion evaluation still works and a released reservation returns its quota")
    void promotionEvaluationStillWorksAndReleaseReturnsTheQuota() {
        Campaign campaign = seedCampaign("QUOTA-EVAL-IT", 1, null, null);
        UUID userId = UUID.randomUUID();

        ReservationResponse response = reservationService.reserveWithRetry("QUOTA-EVAL-IT",
            request(userId, "199.99"));

        assertThat(response.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(response.finalAmount()).isEqualByComparingTo("179.99");
        assertThat(response.code()).isEqualTo("QUOTA-EVAL-IT");

        CouponUsageReservation row = reservationRepository.findById(response.reservationId())
            .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(UsageStatus.PENDING);
        assertThat(row.getOrderAmount()).isEqualByComparingTo("199.99");
        assertThat(row.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(Duration.between(row.getReservedAt(), row.getExpiresAt()).getSeconds())
            .isEqualTo(TTL_SECONDS);
        assertThat(usedCount(campaign.getId())).isEqualTo(1);

        reservationService.releaseWithRetry(response.reservationId());

        CouponUsageReservation released = reservationRepository.findById(response.reservationId())
            .orElseThrow();
        assertThat(released.getStatus()).isEqualTo(UsageStatus.RELEASED);
        assertThat(usedCount(campaign.getId())).isZero();
        assertThat(userUsedCount(campaign.getId(), userId)).isZero();

        ReservationResponse afterRelease = reservationService.reserveWithRetry("QUOTA-EVAL-IT",
            request(userId, "199.99"));
        assertThat(afterRelease.status()).isEqualTo("PENDING");
        assertThat(usedCount(campaign.getId())).isEqualTo(1);
    }
}
