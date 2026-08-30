package com.shop.promotionservice.service;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 *       Runs in BATCHES (never loads the whole backlog) with flush+clear per
 *       batch to bound persistence-context memory.</li>
 *   <li>{@link #purgeOldTerminalReservations()} - retention purge for terminal
 *       RELEASED/EXPIRED rows older than the retention window.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationCleanupScheduler {

    private final CouponUsageReservationRepository reservationRepository;

    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    @Value("${shop.promotion.reservation-cleanup-batch-size:500}")
    private int batchSize;

    @Value("${shop.promotion.reservation-retention-days:30}")
    private int retentionDays;

    @Scheduled(fixedDelayString = "${shop.promotion.reservation-cleanup-interval-ms:60000}")
    @Transactional
    public void releaseAllExpiredReservations() {
        try {
            int total = 0;
            while (true) {
                List<CouponUsageReservation> batch = reservationRepository.findByStatusAndExpiresAtBefore(
                    UsageStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    break;
                }
                batch.forEach(r -> r.setStatus(UsageStatus.EXPIRED));
                total += batch.size();
                entityManager.flush();
                entityManager.clear();
            }
            if (total > 0) {
                log.info("Expired-reservation sweep expired {} reservation(s)", total);
            }
        } catch (Exception ex) {
            // Same posture as the retention job below: log at ERROR with full
            // context instead of letting the scheduler swallow the stack trace.
            // Flushed batches are rolled back with the transaction; the next
            // cycle retries them - safe because status flips and deletes are
            // idempotent.
            log.error("Expired-reservation sweep failed - will retry next cycle", ex);
        }
    }

    @Scheduled(cron = "${shop.promotion.reservation-retention-cron:0 0 4 * * *}")
    @Transactional
    public void purgeOldTerminalReservations() {
        try {
            int deleted = 0;
            while (true) {
                List<CouponUsageReservation> batch = reservationRepository.findTerminalBefore(
                    List.of(UsageStatus.RELEASED, UsageStatus.EXPIRED),
                    Instant.now().minus(retentionDays, ChronoUnit.DAYS),
                    PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    break;
                }
                reservationRepository.deleteAllInBatch(batch);
                deleted += batch.size();
                entityManager.flush();
                entityManager.clear();
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
