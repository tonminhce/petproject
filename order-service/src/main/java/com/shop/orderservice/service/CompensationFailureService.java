package com.shop.orderservice.service;

import com.shop.orderservice.entity.FailedCompensation;
import com.shop.orderservice.repository.FailedCompensationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The ledger of the compensations that failed.
 *
 * <p>A compensation (release the stock reservation of a failed checkout) that
 * fails must not disappear: the order row is deleted right after, so nothing
 * else points at the orphaned hold. The row written here is that link, and the
 * retry below is what eventually releases the hold - instead of waiting for
 * inventory-service's TTL sweep (900 seconds by default) with the sellable
 * stock materially lower than the ledger says.</p>
 *
 * <p>The record is written in its OWN transaction: the saga that failed is
 * rolling back, so a write that joins that transaction would be rolled back
 * with it.</p>
 */
@Service
@Slf4j
public class CompensationFailureService {

    private final FailedCompensationRepository repository;
    private final StockReservationService stockReservationService;

    /** The backoff between two retries of the same compensation. */
    private final Duration retryBackoff;

    @Autowired
    public CompensationFailureService(FailedCompensationRepository repository,
                                      StockReservationService stockReservationService,
                                      @Value("${order.compensation.retry-backoff-seconds:60}") long retryBackoffSeconds) {
        this.repository = repository;
        this.stockReservationService = stockReservationService;
        this.retryBackoff = Duration.ofSeconds(retryBackoffSeconds);
    }

    /**
     * Records a compensation that could not be done.
     *
     * <p>Idempotent per (reservation, kind): a reservation that is already on
     * the list is not recorded twice, so a repeated failure does not pile up
     * rows.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orderId, UUID reservationId, String kind, String error) {
        if (reservationId == null) {
            return;
        }
        if (repository.findFirstByReservationIdAndKindAndStatus(
                reservationId, kind, FailedCompensation.STATUS_PENDING).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        FailedCompensation row = FailedCompensation.builder()
                .orderId(orderId)
                .reservationId(reservationId)
                .kind(kind)
                .status(FailedCompensation.STATUS_PENDING)
                .attempts(0)
                .lastError(error)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(row);
        log.error("Compensation {} failed for reservation {} (order {}) — recorded for the retry: {}",
                kind, reservationId, orderId, error);
    }

    /**
     * Retries the pending compensations.
     *
     * <p>A compensation that succeeds is marked RESOLVED; one that fails again
     * is counted and pushed to the next attempt.</p>
     */
    @Scheduled(fixedDelayString = "${order.compensation.retry-millis:30000}")
    @Transactional
    public int retryPending() {
        Instant now = Instant.now();
        List<FailedCompensation> pending = repository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        FailedCompensation.STATUS_PENDING, now);
        int resolved = 0;
        for (FailedCompensation row : pending) {
            try {
                stockReservationService.release(row.getReservationId());
                row.setStatus(FailedCompensation.STATUS_RESOLVED);
                row.setResolvedAt(now);
                row.setNextAttemptAt(null);
                row.setUpdatedAt(now);
                log.info("Compensation {} for reservation {} resolved on attempt {}",
                        row.getKind(), row.getReservationId(), row.getAttempts() + 1);
                resolved++;
            } catch (Exception ex) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(ex.getMessage());
                row.setNextAttemptAt(now.plus(retryBackoff));
                row.setUpdatedAt(now);
                log.warn("Compensation {} for reservation {} still failing (attempt {}): {}",
                        row.getKind(), row.getReservationId(), row.getAttempts(), ex.getMessage());
            }
            repository.save(row);
        }
        return resolved;
    }
}
