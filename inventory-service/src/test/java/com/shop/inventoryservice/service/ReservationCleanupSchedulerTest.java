package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.entity.ReservationStatus;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
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

@ExtendWith(MockitoExtension.class)
class ReservationCleanupSchedulerTest {

    @Mock ReservationRepository reservationRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock EntityManager entityManager;
    @InjectMocks ReservationCleanupScheduler scheduler;

    private final UUID productId = UUID.randomUUID();
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        // @Value and @PersistenceContext fields are not injected by Mockito - set explicitly
        ReflectionTestUtils.setField(scheduler, "batchSize", 500);
        ReflectionTestUtils.setField(scheduler, "entityManager", entityManager);
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
        verify(entityManager).flush();
        verify(entityManager).clear();
    }

    @Test
    void sweep_noExpired_noWrites() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(ReservationStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.releaseAllExpiredReservations();

        verify(inventoryRepository, never()).findByProductId(any());
        verify(reservationRepository, never()).saveAll(any());
        verify(entityManager, never()).flush();
    }
}
