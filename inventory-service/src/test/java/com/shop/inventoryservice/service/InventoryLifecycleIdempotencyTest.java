package com.shop.inventoryservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.OutboxEventRepository;
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
 * Container-backed lifecycle idempotency tests — bootstrap lives in
 * {@link AbstractIntegrationTest} (Postgres + Kafka, real Liquibase).
 * Seeds reservation rows directly at each terminal state and asserts the
 * hardening spec §7.1 contract: retried terminal calls are no-ops, wrong-way
 * transitions stay rejected.
 */
class InventoryLifecycleIdempotencyTest extends AbstractIntegrationTest {

    @Autowired ReservationRepository reservations;
    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventories;
    @Autowired OutboxEventRepository outboxEvents;

    /**
     * A no-op retry must publish nothing — asserted via the outbox row count,
     * which only ever grows when the transactional publisher writes a row
     * (the relay flips status, never deletes; the retention scheduler is a
     * 3am cron), so count stability here proves no new event was emitted.
     */
    private void assertNoMutation(UUID productId, Inventory before, long outboxBefore) {
        Inventory after = inventories.findByProductId(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(before.getAvailableQuantity());
        assertThat(after.getReservedQuantity()).isEqualTo(before.getReservedQuantity());
        assertThat(outboxEvents.count()).isEqualTo(outboxBefore);
    }

    UUID seedReserved(UUID productId, int qty, ReservationStatus status, Instant expiresAt) {
        inventories.save(Inventory.builder()
            .productId(productId)
            .availableQuantity(100)
            .reservedQuantity(qty)
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
    void commit_twice_secondIsNoop() {
        UUID productId = UUID.randomUUID();
        UUID id = seedReserved(productId, 5, ReservationStatus.PENDING,
            Instant.now().plusSeconds(600));
        inventoryService.commit(id);
        Inventory before = inventories.findByProductId(productId).orElseThrow();
        long outboxBefore = outboxEvents.count();

        assertThatCode(() -> inventoryService.commit(id)).doesNotThrowAnyException();

        assertNoMutation(productId, before, outboxBefore);
    }

    @Test
    void commit_afterRelease_throwsInvalidState() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.RELEASED,
            Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> inventoryService.commit(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_INVALID_STATE.getCode()));
    }

    @Test
    void commit_expiredPending_throwsExpired() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.PENDING,
            Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> inventoryService.commit(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_EXPIRED.getCode()));
    }

    @Test
    void release_afterReleaseOrExpired_isNoop() {
        UUID prodA = UUID.randomUUID();
        UUID prodB = UUID.randomUUID();
        UUID a = seedReserved(prodA, 5, ReservationStatus.RELEASED, Instant.now().plusSeconds(600));
        UUID b = seedReserved(prodB, 5, ReservationStatus.EXPIRED, Instant.now().plusSeconds(600));
        Inventory beforeA = inventories.findByProductId(prodA).orElseThrow();
        Inventory beforeB = inventories.findByProductId(prodB).orElseThrow();
        long outboxBefore = outboxEvents.count();

        assertThatCode(() -> { inventoryService.release(a); inventoryService.release(b); })
            .doesNotThrowAnyException();

        assertNoMutation(prodA, beforeA, outboxBefore);
        assertNoMutation(prodB, beforeB, outboxBefore);
    }

    @Test
    void release_afterCommitted_throwsInvalidState() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.COMMITTED, Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> inventoryService.release(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_INVALID_STATE.getCode()));
    }
}
