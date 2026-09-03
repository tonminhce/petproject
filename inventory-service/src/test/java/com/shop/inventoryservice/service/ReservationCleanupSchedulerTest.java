package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
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
    void sweep_releasesExpiredAndAdjustsReservedQuantity() {
        Reservation expired = Reservation.builder()
            .id(UUID.randomUUID()).productId(productId).quantity(5)
            .status(ReservationStatus.PENDING)
            .expiresAt(Instant.now().minusSeconds(60)).build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(expired))
            .thenReturn(List.of());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        scheduler.releaseAllExpiredReservations();

        assertThat(inventory.getReservedQuantity()).isEqualTo(3);
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(inventoryRepository).save(inventory);
        verify(reservationRepository).saveAll(List.of(expired));
        // Cache invariant — every batch that mutates Inventory.reservedQuantity
        // must drop the corresponding cache entry.
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void sweep_noExpired_noWrites() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.releaseAllExpiredReservations();

        verify(inventoryRepository, never()).findByProductId(any());
        verify(reservationRepository, never()).saveAll(any());
        verify(cacheService, never()).evictAfterCommit(any());
    }
}
