package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class ReservationCleanupSchedulerTest {

    @Mock ReservationRepository reservationRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock PlatformTransactionManager txManager;
    @Mock InventoryCacheService cacheService;
    @Mock InventoryEventPublisher publisher;
    @InjectMocks ReservationCleanupScheduler scheduler;

    private final UUID productId = UUID.randomUUID();
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 500);
        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        inventory = Inventory.builder()
            .id(UUID.randomUUID()).productId(productId)
            .availableQuantity(100).reservedQuantity(8).build();
    }

    @Test
    void sweep_claimsExpiredRowsAndReleasesReservedQuantity() {
        Reservation expired = Reservation.builder()
            .id(UUID.randomUUID()).productId(productId).quantity(5)
            .status(ReservationStatus.PENDING)
            .expiresAt(Instant.now().minusSeconds(60)).build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(expired))
            .thenReturn(List.of());
        // F-011 — the sweep CLAIMS the row before it moves any stock.
        when(reservationRepository.claimForExpiry(
                expired.getId(), ReservationStatus.PENDING, ReservationStatus.EXPIRED)).thenReturn(1);
        when(inventoryRepository.releaseReserved(productId, 5)).thenReturn(1);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        scheduler.releaseAllExpiredReservations();

        verify(reservationRepository).claimForExpiry(
                expired.getId(), ReservationStatus.PENDING, ReservationStatus.EXPIRED);
        verify(inventoryRepository).releaseReserved(productId, 5);
        verify(inventoryRepository, never()).save(any());
        verify(reservationRepository, never()).saveAll(any());
        // Cache invariant — every batch that mutates Inventory.reservedQuantity
        // must drop the corresponding cache entry.
        verify(cacheService).evictAfterCommit(productId);
    }

    /**
     * F-012 — the expiry sweep is a stock movement and must emit the same
     * inventory.released.v1 event as the service-layer release path, in the same
     * transaction, otherwise shop.inventory.events.v1 under-reports releases and
     * the outbox stops describing the domain state.
     */
    @Test
    void sweep_publishesReleasedEventForEachExpiredReservation() {
        Reservation expired = Reservation.builder()
            .id(UUID.randomUUID()).productId(productId).quantity(5)
            .status(ReservationStatus.PENDING)
            .expiresAt(Instant.now().minusSeconds(60)).build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(expired))
            .thenReturn(List.of());
        when(reservationRepository.claimForExpiry(
                expired.getId(), ReservationStatus.PENDING, ReservationStatus.EXPIRED)).thenReturn(1);
        when(inventoryRepository.releaseReserved(productId, 5)).thenReturn(1);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        scheduler.releaseAllExpiredReservations();

        verify(publisher).publishReleased(inventory, expired, "PENDING");
    }

    /**
     * F-011 regression — the lost-update guard. When the claim affects zero
     * rows, another writer already transitioned the reservation (a concurrent
     * commit(), or a competing sweep). The sweep must then NOT touch the
     * inventory counters at all: a committed reservation must never have its
     * stock released by the expiry path.
     */
    @Test
    void sweep_skipsRowsAlreadyClaimedByAnotherWriter() {
        Reservation raced = Reservation.builder()
            .id(UUID.randomUUID()).productId(productId).quantity(5)
            .status(ReservationStatus.PENDING)
            .expiresAt(Instant.now().minusSeconds(60)).build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(raced))
            .thenReturn(List.of());
        // The claim lost the race - the row is no longer PENDING.
        when(reservationRepository.claimForExpiry(
                raced.getId(), ReservationStatus.PENDING, ReservationStatus.EXPIRED)).thenReturn(0);

        scheduler.releaseAllExpiredReservations();

        verify(inventoryRepository, never()).releaseReserved(any(), anyInt());
        verify(inventoryRepository, never()).save(any());
        verify(reservationRepository, never()).saveAll(any());
        verify(cacheService, never()).evictAfterCommit(any());
    }

    @Test
    void sweep_noExpired_noWrites() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.releaseAllExpiredReservations();

        verify(inventoryRepository, never()).findByProductId(any());
        verify(inventoryRepository, never()).releaseReserved(any(), anyInt());
        verify(reservationRepository, never()).saveAll(any());
        verify(reservationRepository, never()).claimForExpiry(any(), any(), any());
        verify(cacheService, never()).evictAfterCommit(any());
    }
}
