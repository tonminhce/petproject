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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Background jobs:
 * <ol>
 *   <li>{@link #releaseAllExpiredReservations()} - releases stock held by PENDING
 *       reservations whose TTL elapsed. Without it, reservedQuantity stays inflated
 *       between expiry and the next reserve() call -> spurious STOCK_INSUFFICIENT.
 *       Runs in BATCHES (never loads the whole backlog) with flush+clear per batch
 *       to bound persistence-context memory.</li>
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

    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    @Value("${inventory.reservation-cleanup-batch-size:500}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${inventory.reservation-cleanup-interval-ms:60000}")
    @Transactional
    public void releaseAllExpiredReservations() {
        try {
            int total = 0;
            while (true) {
                List<Reservation> batch = reservationRepository.findByStatusAndExpiresAtBefore(
                    ReservationStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    break;
                }
                releaseBatch(batch);
                total += batch.size();
                entityManager.flush();
                entityManager.clear();
            }
            if (total > 0) {
                log.info("Expired-reservation sweep released {} reservation(s)", total);
            }
        } catch (Exception ex) {
            // Same posture as the retention jobs below: log at ERROR with full
            // context instead of letting the scheduler swallow the stack trace.
            // Batches already flushed stay committed; the next cycle continues.
            log.error("Expired-reservation sweep failed - will retry next cycle", ex);
        }
    }

    private void releaseBatch(List<Reservation> batch) {
        Map<UUID, List<Reservation>> byProduct = batch.stream()
            .collect(Collectors.groupingBy(Reservation::getProductId));
        byProduct.forEach((productId, reservations) -> {
            int quantity = reservations.stream().mapToInt(Reservation::getQuantity).sum();
            inventoryRepository.findByProductId(productId).ifPresent(inv -> {
                inv.setReservedQuantity(Math.max(0, inv.getReservedQuantity() - quantity));
                inv.setLastUpdated(Instant.now());
                reservations.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));
                inventoryRepository.save(inv);
                reservationRepository.saveAll(reservations);
                // Same invariant as the service-layer write paths: any mutation to
                // Inventory.reservedQuantity must drop the cached entry, otherwise a
                // hot GET /api/v1/inventory/{id} reads a stale value for up to 60s.
                cacheService.evictAfterCommit(productId);
            });
        });
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
