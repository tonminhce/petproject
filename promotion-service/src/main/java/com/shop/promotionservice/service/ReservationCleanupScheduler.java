package com.shop.promotionservice.service;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;
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
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.promotion.reservation-cleanup-batch-size:500}")
    private int batchSize;

    @Value("${shop.promotion.reservation-retention-days:30}")
    private int retentionDays;

    public ReservationCleanupScheduler(
            CouponUsageReservationRepository reservationRepository,
            PlatformTransactionManager txManager) {
        this.reservationRepository = reservationRepository;
        // A16: each batch is its own transaction. A failure mid-cycle rolls back
        // only the failing batch — earlier successful batches stay committed.
        // The original single-@Transactional + try/catch-inside was wrong (the
        // catch swallowed the exception so the outer tx COMMITTED partial work;
        // the javadoc claimed the opposite).
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.promotion.reservation-cleanup-interval-ms:60000}")
    public void releaseAllExpiredReservations() {
        try {
            int total = 0;
            while (true) {
                Integer processed = transactionTemplate.execute(status -> {
                    List<CouponUsageReservation> batch = reservationRepository.findByStatusAndExpiresAtBefore(
                        UsageStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize));
                    if (batch.isEmpty()) {
                        return null;
                    }
                    batch.forEach(r -> r.setStatus(UsageStatus.EXPIRED));
                    return batch.size();
                });
                if (processed == null || processed == 0) {
                    break;
                }
                total += processed;
            }
            if (total > 0) {
                log.info("Expired-reservation sweep expired {} reservation(s)", total);
            }
        } catch (Exception ex) {
            // Per-batch tx: earlier successful batches are committed; the failing batch
            // is rolled back. The next cycle retries from where this one stopped —
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
