package com.shop.promotionservice.service;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Background jobs:
 * <ol>
 *   <li>{@link #releaseAllExpiredReservations()} - flips PENDING reservations
 *       whose TTL elapsed to EXPIRED. Without it, per-user/campaign quotas stay
 *       consumed by dead holds (quota counts are by-status, so the flip is the
 *       release). Unlike inventory's sweep there are NO stock rows to adjust,
 *       NO cache to evict and NO events to publish — only the status changes.
 *       Runs in BATCHES (never loads the whole backlog) with one transaction
 *       per batch so a mid-cycle failure leaves earlier successful batches
 *       committed (status flips are idempotent — the next cycle resumes).</li>
 *   <li>{@link #purgeOldTerminalReservations()} - retention purge for terminal
 *       RELEASED/EXPIRED rows older than the retention window.</li>
 * </ol>
 */
@Component
@Slf4j
public class ReservationCleanupScheduler {

    private final CouponUsageReservationRepository reservationRepository;
    private final CampaignRepository campaignRepository;
    private final PromotionEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.promotion.reservation-cleanup-batch-size:500}")
    private int batchSize;

    @Value("${shop.promotion.reservation-retention-days:30}")
    private int retentionDays;

    public ReservationCleanupScheduler(
            CouponUsageReservationRepository reservationRepository,
            CampaignRepository campaignRepository,
            PromotionEventPublisher eventPublisher,
            PlatformTransactionManager txManager) {
        this.reservationRepository = reservationRepository;
        this.campaignRepository = campaignRepository;
        this.eventPublisher = eventPublisher;
        // A16: each batch is its own transaction. A failure mid-cycle rolls back
        // The original single-@Transactional + try/catch-inside was wrong (the
        // catch swallowed the exception so the outer tx COMMITTED partial work;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /**
     * The first run is delayed by one interval instead of firing at context
     * startup (same reasoning as the inventory sweep). A startup-time sweep
     * competes with the first application requests and, in an integration
     * test, with the other test classes that share the same database.
     */
    @Scheduled(fixedDelayString = "${shop.promotion.reservation-cleanup-interval-ms:60000}",
               initialDelayString = "${shop.promotion.reservation-cleanup-initial-delay-ms:60000}")
    public void releaseAllExpiredReservations() {
        try {
            int total = 0;
            java.util.concurrent.atomic.AtomicInteger expiredCounter =
                    new java.util.concurrent.atomic.AtomicInteger();
            while (true) {
                Integer processed = transactionTemplate.execute(status -> {
                    List<CouponUsageReservation> batch = reservationRepository.findByStatusAndExpiresAtBefore(
                        UsageStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize));
                    if (batch.isEmpty()) {
                        return null;
                    }
                    // another writer already transitioned (a concurrent commit()
                    // COMMITTED redemption can never be reverted to EXPIRED.
                    Instant now = Instant.now();
                    int expired = 0;
                    for (CouponUsageReservation reservation : batch) {
                        int claimed = reservationRepository.claimForExpiry(
                            reservation.getId(), UsageStatus.PENDING, UsageStatus.EXPIRED, now);
                        if (claimed == 0) {
                            continue;
                        }
                        expired++;
                        // release path, in the same transaction. Without it,
                        // was never released (the inventory sweep already does
                        reservation.setReleasedAt(now);
                        campaignRepository.findById(reservation.getCampaignId()).ifPresent(campaign ->
                                eventPublisher.publishReleased(campaign, reservation, UsageStatus.PENDING.name()));
                    }
                    expiredCounter.addAndGet(expired);
                    return batch.size();
                });
                if (processed == null || processed == 0) {
                    break;
                }
                total += processed;
            }
            if (total > 0) {
                log.info("Expired-reservation sweep expired {} reservation(s)", expiredCounter.get());
            }
        } catch (Exception ex) {
            // safe because status flips are idempotent.
            log.error("Expired-reservation sweep failed at batch boundary - will retry next cycle", ex);
        }
    }

    @Scheduled(cron = "${shop.promotion.reservation-retention-cron:0 0 4 * * *}")
    public void purgeOldTerminalReservations() {
        try {
            int deleted = 0;
            while (true) {
                Integer processed = transactionTemplate.execute(status -> {
                    List<CouponUsageReservation> batch = reservationRepository.findTerminalBefore(
                        List.of(UsageStatus.RELEASED, UsageStatus.EXPIRED),
                        Instant.now().minus(retentionDays, ChronoUnit.DAYS),
                        PageRequest.of(0, batchSize));
                    if (batch.isEmpty()) {
                        return null;
                    }
                    reservationRepository.deleteAllInBatch(batch);
                    return batch.size();
                });
                if (processed == null || processed == 0) {
                    break;
                }
                deleted += processed;
            }
            if (deleted > 0) {
                log.info("Purged {} terminal reservation(s) older than {} day(s)",
                    deleted, retentionDays);
            }
        } catch (Exception ex) {
            log.error("Reservation retention purge failed - needs ops attention", ex);
        }
    }
}
