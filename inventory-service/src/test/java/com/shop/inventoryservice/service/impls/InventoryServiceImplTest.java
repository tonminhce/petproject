package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.mapper.InventoryMapper;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock InventoryMapper mapper;
    @Mock InventoryEventPublisher publisher;
    @Mock InventoryCacheService cacheService;
    @InjectMocks InventoryServiceImpl service;

    private final UUID productId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private Inventory inventory;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        inventory = Inventory.builder()
            .id(UUID.randomUUID())
            .productId(productId)
            .availableQuantity(100)
            .reservedQuantity(0)
            .version(0L)
            .lastUpdated(Instant.now())
            .build();
        reservation = Reservation.builder()
            .id(reservationId)
            .productId(productId)
            .quantity(5)
            .status(ReservationStatus.PENDING)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900))
            .build();
    }

    @Test
    void findById_returnsInventoryResponse() {
        InventoryResponse resp = new InventoryResponse(productId, 100, 0, inventory.getLastUpdated());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(mapper.toResponse(inventory)).thenReturn(resp);

        assertThat(service.findById(productId)).isEqualTo(resp);
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(productId))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndPublishesAdjusted() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        when(inventoryRepository.existsByProductId(productId)).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(inventory);
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(mapper.toResponse(inventory)).thenReturn(new InventoryResponse(productId, 50, 0, null));

        var result = service.create(req);

        assertThat(result.availableQuantity()).isEqualTo(50);
        verify(publisher).publishAdjusted(inventory);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void create_throwsConflictWhenExists() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        when(inventoryRepository.existsByProductId(productId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void reserve_incrementsReservedAndPublishes() {
        ReserveRequest req = new ReserveRequest(5, null);
        when(reservationRepository.findByProductIdAndStatusAndExpiresAtBefore(
            eq(productId), eq(ReservationStatus.PENDING), any(Instant.class)))
            .thenReturn(List.of());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(any(Reservation.class))).thenReturn(reservation);
        when(mapper.toReservationResponse(any(Reservation.class)))
            .thenReturn(new ReservationResponse(reservationId, productId, 5, ReservationStatus.PENDING,
                reservation.getExpiresAt(), null));

        var result = service.reserve(productId, req);

        assertThat(result.quantity()).isEqualTo(5);
        assertThat(inventory.getReservedQuantity()).isEqualTo(5);
        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(publisher).publishReserved(eq(inventory), reservationCaptor.capture());
        Reservation published = reservationCaptor.getValue();
        assertThat(published.getProductId()).isEqualTo(productId);
        assertThat(published.getQuantity()).isEqualTo(5);
        assertThat(published.getStatus()).isEqualTo(ReservationStatus.PENDING);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void reserve_throwsStockInsufficient() {
        ReserveRequest req = new ReserveRequest(999, null);
        when(reservationRepository.findByProductIdAndStatusAndExpiresAtBefore(
            eq(productId), eq(ReservationStatus.PENDING), any(Instant.class)))
            .thenReturn(List.of());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> service.reserve(productId, req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void commit_movesStockAndPublishes() {
        inventory.setReservedQuantity(5);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        service.commit(reservationId);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(95);
        assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verify(publisher).publishCommitted(inventory, reservation);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void commit_throwsWhenNotPending() {
        // COMMITTED is now an idempotent no-op (hardening §7.1) — wrong-way
        // terminal states must still be rejected.
        reservation.setStatus(ReservationStatus.RELEASED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.commit(reservationId))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void release_freesReservedAndPublishes() {
        inventory.setReservedQuantity(5);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        service.release(reservationId);

        assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(publisher).publishReleased(inventory, reservation, "PENDING");
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void delete_removesInventoryAndPublishes() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(reservationRepository.countByProductIdAndStatusIn(eq(productId), anyList())).thenReturn(0L);
        doNothing().when(inventoryRepository).delete(inventory);

        service.delete(productId);

        verify(inventoryRepository).delete(inventory);
        verify(publisher).publishDeleted(inventory);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void delete_throwsInventoryInUse_whenReservationActive() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(reservationRepository.countByProductIdAndStatusIn(eq(productId), anyList())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(productId))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("INV-3009"));
        verify(inventoryRepository, never()).delete(any());
    }

    @Test
    void update_throwsBadRequest_whenBodyProductIdMismatch() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(UUID.randomUUID(), 50);

        assertThatThrownBy(() -> service.update(productId, req))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("ERR-0400"));
        verify(inventoryRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Cache-invariants: every write path that touches Inventory must drop the
    // cached entry via InventoryCacheService.evictAfterCommit. These tests are
    // the regression net — if a new write method is added and forgets the evict,
    // the suite goes red.
    // -----------------------------------------------------------------

    @Test
    void create_evictsCacheAfterCommit() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        when(inventoryRepository.existsByProductId(productId)).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(inventory);
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(mapper.toResponse(inventory)).thenReturn(new InventoryResponse(productId, 50, 0, null));

        service.create(req);

        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void update_evictsCacheAfterCommit() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 75);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(mapper.toResponse(inventory)).thenReturn(new InventoryResponse(productId, 75, 0, null));

        service.update(productId, req);

        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void delete_evictsCacheAfterCommit() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(reservationRepository.countByProductIdAndStatusIn(eq(productId), anyList())).thenReturn(0L);

        service.delete(productId);

        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void reserve_evictsCacheAfterCommit() {
        ReserveRequest req = new ReserveRequest(5, null);
        when(reservationRepository.findByProductIdAndStatusAndExpiresAtBefore(
                eq(productId), eq(ReservationStatus.PENDING), any(Instant.class))).thenReturn(List.of());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(any(Reservation.class))).thenReturn(reservation);

        service.reserve(productId, req);

        verify(cacheService, atLeastOnce()).evictAfterCommit(productId);
    }

    @Test
    void commit_evictsCacheAfterCommit() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        service.commit(reservationId);

        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void release_evictsCacheAfterCommit() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        service.release(reservationId);

        verify(cacheService).evictAfterCommit(productId);
    }
}
