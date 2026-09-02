package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wraps {@link InventoryService} reservation operations with a manual retry
 * loop for {@link OptimisticLockingFailureException}. A retry re-reads the
 * entity (fresh @Version) and re-validates - safe for the low-contention
 * internal call pattern.
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 50L;

    private final InventoryService inventoryService;

    @Override
    public ReservationResponse reserveWithRetry(UUID productId, ReserveRequest request) {
        return withRetry(() -> inventoryService.reserve(productId, request), productId);
    }

    @Override
    public void commitWithRetry(UUID reservationId) {
        withRetry(() -> { inventoryService.commit(reservationId); return null; }, reservationId);
    }

    @Override
    public void releaseWithRetry(UUID reservationId) {
        withRetry(() -> { inventoryService.release(reservationId); return null; }, reservationId);
    }

    @Override
    public void releaseCommittedWithRetry(UUID reservationId) {
        withRetry(() -> { inventoryService.releaseCommitted(reservationId); return null; }, reservationId);
    }

    private <T> T withRetry(Supplier<T> operation, UUID resourceId) {
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, resourceId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
