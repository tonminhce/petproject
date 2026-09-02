package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock InventoryService inventoryService;
    @InjectMocks ReservationServiceImpl service;

    private final UUID productId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    @Test
    void reserveWithRetry_retriesOnOptimisticLockFailure() {
        ReserveRequest req = new ReserveRequest(5, null);
        ReservationResponse resp = new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING,
            Instant.now(), Instant.now().plusSeconds(900), null, null, null);

        when(inventoryService.reserve(productId, req))
            .thenThrow(new OptimisticLockingFailureException("conflict"))
            .thenReturn(resp);

        ReservationResponse result = service.reserveWithRetry(productId, req);

        assertThat(result).isEqualTo(resp);
        verify(inventoryService, times(2)).reserve(productId, req);
    }

    @Test
    void commitWithRetry_retriesVoidOperationOnOptimisticLockFailure() {
        doThrow(new OptimisticLockingFailureException("conflict"))
            .doNothing().when(inventoryService).commit(reservationId);

        service.commitWithRetry(reservationId);

        verify(inventoryService, times(2)).commit(reservationId);
    }

    @Test
    void reserveWithRetry_throwsVersionConflictAfterMaxRetries() {
        ReserveRequest req = new ReserveRequest(5, null);
        when(inventoryService.reserve(productId, req))
            .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> service.reserveWithRetry(productId, req))
            .isInstanceOf(BusinessException.class);
        verify(inventoryService, times(3)).reserve(productId, req);
    }

    @Test
    void reserveWithRetry_passesThroughSuccess() {
        ReserveRequest req = new ReserveRequest(5, null);
        ReservationResponse resp = new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING,
            Instant.now(), Instant.now().plusSeconds(900), null, null, null);
        when(inventoryService.reserve(productId, req)).thenReturn(resp);

        assertThat(service.reserveWithRetry(productId, req)).isEqualTo(resp);
        verify(inventoryService, times(1)).reserve(productId, req);
    }
}
