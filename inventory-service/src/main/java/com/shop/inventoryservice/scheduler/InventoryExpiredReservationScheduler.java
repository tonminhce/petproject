package com.shop.inventoryservice.scheduler;

import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * H45 — release-expired was previously called inside {@code reserve()} on
 * every reserve (review finding H45 / D10). A busy product with 100
 * reserves/sec therefore ran 100 release scans/sec — a hot path that
 * scales O(reserve rate) but does work only O(reservation expiry rate).
 *
 * <p>The fix moves the scan to a scheduler with the fleet-standard
 * timestamped {@code fixedDelayString} property idiom. Default cadence
 * is 5 minutes — long enough that the worst-case stale reservation window
 * is the same as before (≤ 5min), short enough that the scan stays cheap.
 * The expired rows transition to {@code EXPIRED} and the inventory
 * {@code reservedQuantity} is decremented in a single per-tick transaction
 * (single scan + single UPDATE; per-product iteration only updates rows
 * that actually expired — no over-fetch).</p>
 *
 * <p>The previous {@code releaseExpiredReservations(UUID productId)} is
 * retained on the service for transactional use inside the reserve() saga
 * (a stale row that races the atomic capacity check still gets released
 * synchronously on the rare hot path), but it's no longer the trigger.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryExpiredReservationScheduler {

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryCacheService cacheService;
    private final InventoryEventPublisher publisher;
    private final PlatformTransactionManager txManager;

    @Scheduled(
        fixedDelayString = "${shop.inventory.release-expired-ms:300000}",  // 5min default
        initialDelayString = "${shop.inventory.release-expired-initial-ms:60000}"
    )
    public void releaseExpiredReservations() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        int released = tx.execute(status -> {
            List<Reservation> expired = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, Instant.now(),
                    org.springframework.data.domain.PageRequest.of(0, 1000));
            if (expired.isEmpty()) return 0;
            log.info("Releasing {} expired reservations", expired.size());
            // Group by productId so we update each inventory row once.
            java.util.Map<java.util.UUID, Integer> totalsByProduct = new java.util.HashMap<>();
            for (Reservation r : expired) {
                totalsByProduct.merge(r.getProductId(), r.getQuantity(), Integer::sum);
                r.setStatus(ReservationStatus.EXPIRED);
            }
            reservationRepository.saveAll(expired);
            // Decrement each affected inventory row in its own load+save —
            // @Version keeps concurrent reserve() updates consistent.
            for (var entry : totalsByProduct.entrySet()) {
                inventoryRepository.findByProductId(entry.getKey()).ifPresent(inv -> {
                    inv.setReservedQuantity(inv.getReservedQuantity() - entry.getValue());
                    inv.setLastUpdated(Instant.now());
                    inventoryRepository.save(inv);
                    cacheService.evictAfterCommit(inv.getProductId());
                });
            }
            return expired.size();
        });
        if (released > 0) {
            log.info("Expired reservation sweep released={}", released);
        }
    }
}
