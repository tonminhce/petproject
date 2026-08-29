package com.shop.inventoryservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Container-backed releaseCommitted tests — bootstrap lives in
 * {@link AbstractIntegrationTest} (Postgres + Kafka, real Liquibase).
 * Asserts the hardening spec §7.2 contract: a COMMITTED reservation released
 * after the fact restocks availableQuantity (half-commit rollback), retried
 * calls on terminal states are no-ops, and PENDING rows stay on the plain
 * release path (rejected here).
 */
class ReleaseCommittedTest extends AbstractIntegrationTest {

    @Autowired ReservationRepository reservations;
    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventories;

    /**
     * Seeds inventory + reservation rows directly. For COMMITTED status the
     * inventory reflects the post-commit state — commit() already moved
     * reservedQuantity→0 and availableQuantity -= qty — so the releaseCommitted
     * restock assertion measures the rollback against a realistic state.
     */
    UUID seedReserved(UUID productId, int qty, ReservationStatus status, Instant expiresAt) {
        boolean committed = status == ReservationStatus.COMMITTED;
        inventories.save(Inventory.builder()
            .productId(productId)
            .availableQuantity(committed ? 100 - qty : 100)
            .reservedQuantity(committed ? 0 : qty)
            .lastUpdated(Instant.now())
            .build());
        Reservation r = Reservation.builder()
            .productId(productId)
            .quantity(qty)
            .status(status)
            .createdAt(Instant.now())
            .expiresAt(expiresAt)
            .build();
        return reservations.save(r).getId();
    }

    @Test
    void releaseCommitted_restocks_andTerminalizes() {
        UUID productId = UUID.randomUUID();
        UUID id = seedReserved(productId, 5, ReservationStatus.COMMITTED, Instant.now().plusSeconds(600));

        inventoryService.releaseCommitted(id);

        Inventory inv = inventories.findByProductId(productId).orElseThrow();
        assertThat(inv.getAvailableQuantity()).isEqualTo(100);   // restored
        Reservation r = reservations.findById(id).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(r.getReleasedAt()).isNotNull();
    }

    @Test
    void releaseCommitted_isIdempotent_onAlreadyReleasedOrExpired() {
        UUID releasedProductId = UUID.randomUUID();
        UUID releasedId = seedReserved(releasedProductId, 5, ReservationStatus.RELEASED,
            Instant.now().plusSeconds(600));
        UUID expiredProductId = UUID.randomUUID();
        UUID expiredId = seedReserved(expiredProductId, 5, ReservationStatus.EXPIRED,
            Instant.now().plusSeconds(600));

        assertThatCode(() -> {
            inventoryService.releaseCommitted(releasedId);
            inventoryService.releaseCommitted(expiredId);
        }).doesNotThrowAnyException();

        // No-op means no double restock and no status churn.
        Inventory releasedInv = inventories.findByProductId(releasedProductId).orElseThrow();
        assertThat(releasedInv.getAvailableQuantity()).isEqualTo(100);
        assertThat(reservations.findById(releasedId).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.RELEASED);
        Inventory expiredInv = inventories.findByProductId(expiredProductId).orElseThrow();
        assertThat(expiredInv.getAvailableQuantity()).isEqualTo(100);
        assertThat(expiredInv.getReservedQuantity()).isEqualTo(5);
        assertThat(reservations.findById(expiredId).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void releaseCommitted_onPending_throwsInvalidState() {
        UUID productId = UUID.randomUUID();
        UUID id = seedReserved(productId, 5, ReservationStatus.PENDING, Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> inventoryService.releaseCommitted(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_INVALID_STATE.getCode()));

        // Rejected, not mutated — plain release() is the PENDING path.
        assertThat(reservations.findById(id).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.PENDING);
        assertThat(inventories.findByProductId(productId).orElseThrow().getAvailableQuantity())
            .isEqualTo(100);
    }
}
