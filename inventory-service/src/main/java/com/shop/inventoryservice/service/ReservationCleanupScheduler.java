package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Background jobs:
 * <ol>
 *   <li>{@link #releaseAllExpiredReservations()} - releases stock held by PENDING
 *       reservations whose TTL elapsed. Runs in BATCHES with per-batch transaction
 *       boundaries to avoid holding long row locks.</li>
 *   <li>{@link #purgeOldExpiredReservations()} - retention purge for terminal
 *       EXPIRED rows.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationCleanupScheduler {

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryCacheService cacheService;
    private final InventoryEventPublisher publisher;
    private final PlatformTransactionManager txManager;

    @Value("${inventory.reservation-cleanup-batch-size:500}")
    private int batchSize;

    /**
     * The first run is delayed by one interval instead of firing at context
     * startup. A startup-time sweep competes with the first application
     * requests (and, in an integration test, with the other test classes that
     * share the same database), and it removes the "a background job inserts an
     * outbox row while another test is asserting on the outbox table" race.
     */
    @Scheduled(fixedDelayString = "${inventory.reservation-cleanup-interval-ms:60000}",
               initialDelayString = "${inventory.reservation-cleanup-initial-delay-ms:60000}")
    public void releaseAllExpiredReservations() {
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        try {
            int total = 0;
            while (true) {
                Integer processed = txTemplate.execute(status -> {
                    List<Reservation> batch = reservationRepository.findByStatusAndExpiresAtBefore(
                        ReservationStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize));
                    if (batch.isEmpty()) {
                        return 0;
                    }
                    releaseBatch(batch);
                    return batch.size();
                });
                if (processed == null || processed == 0) {
                    break;
                }
                total += processed;
            }
            if (total > 0) {
                log.info("Expired-reservation sweep released {} reservation(s)", total);
            }
        } catch (Exception ex) {
            log.error("Expired-reservation sweep failed - will retry next cycle", ex);
        }
    }

    /**
     * Release the hold of every expired reservation in the batch.
     * <p>Each row is CLAIMED with a conditional UPDATE
     * ({@code status = PENDING} -> {@code status = EXPIRED}) before any stock is
     * moved. A row that another writer already transitioned (a concurrent
     * {@code commit()}, or a competing sweep) affects zero rows and is skipped,
     * so a committed reservation can never be flipped back to EXPIRED and the
     * stock counters are decremented exactly once per reservation.</p>
     * <p>The inventory decrement itself is an atomic guarded UPDATE
     * ({@code reservedQuantity >= qty}), so the counter can never go negative
     * and a double release cannot over-credit stock. The previous
     * {@code Math.max(0, ...)} clamp silently masked exactly that inconsistency;
     * a short balance is now logged as an error for an operator instead.</p>
     */
    private void releaseBatch(List<Reservation> batch) {
        Map<UUID, Integer> claimedByProduct = new LinkedHashMap<>();
        for (Reservation reservation : batch) {
            int claimed = reservationRepository.claimForExpiry(
                reservation.getId(), ReservationStatus.PENDING, ReservationStatus.EXPIRED);
            if (claimed == 0) {
                continue;
            }
            claimedByProduct.merge(reservation.getProductId(), reservation.getQuantity(), Integer::sum);
        }
        claimedByProduct.forEach((productId, quantity) -> {
            int released = inventoryRepository.releaseReserved(productId, quantity);
            if (released == 0) {
                log.error("Expiry sweep could not release {} reserved unit(s) for product {} - "
                        + "the reserved counter is lower than the claimed holds. "
                        + "Stock accounting needs manual reconciliation.", quantity, productId);
            }
            // Same invariant as the service-layer write paths: any mutation to
            // Inventory.reservedQuantity must drop the cached entry, otherwise a
            cacheService.evictAfterCommit(productId);
        });

        // one outbox row per expired reservation, in the same transaction as the
        for (Reservation reservation : batch) {
            inventoryRepository.findByProductId(reservation.getProductId()).ifPresent(inventory -> {
                publisher.publishReleased(inventory, reservation, "PENDING");
            });
        }
    }

    @Scheduled(cron = "${inventory.reservation-retention-cron:0 0 4 * * *}")
    @Transactional
    public void purgeOldExpiredReservations() {
        try {
            int deleted = reservationRepository.deleteByStatusAndCreatedAtBefore(
                ReservationStatus.EXPIRED, Instant.now().minus(30, ChronoUnit.DAYS));
            if (deleted > 0) {
                log.info("Purged {} EXPIRED reservations older than 30 days", deleted);
            }
        } catch (Exception ex) {
            log.error("Reservation retention purge failed - needs ops attention", ex);
        }
    }
}
