package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

@Service
@Slf4j
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
    public ReservationResponse reserveWithRetry(UUID productId, ReserveRequest request, String idempotencyKey) {
        return withRetry(() -> reserveIdempotently(productId, request, idempotencyKey), productId);
    }

    private ReservationResponse reserveIdempotently(UUID productId, ReserveRequest request, String idempotencyKey) {
        try {
            return inventoryService.reserve(productId, request, idempotencyKey);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw ex;
            }
            log.info("Reserve for product {} lost the idempotency-key race - replaying the committed reservation",
                productId);
            return inventoryService.reserve(productId, request, idempotencyKey);
        }
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
                if (!sleep(BACKOFF_BASE_MS * attempt)) {
                    Thread.currentThread().interrupt();
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, resourceId);
                }
            }
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
