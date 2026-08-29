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
        int attempt = 0;
        while (true) {
            try {
                return inventoryService.reserve(productId, request);
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, productId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    @Override
    public void commitWithRetry(UUID reservationId) {
        int attempt = 0;
        while (true) {
            try {
                inventoryService.commit(reservationId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, reservationId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    @Override
    public void releaseWithRetry(UUID reservationId) {
        int attempt = 0;
        while (true) {
            try {
                inventoryService.release(reservationId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, reservationId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    @Override
    public void releaseCommittedWithRetry(UUID reservationId) {
        int attempt = 0;
        while (true) {
            try {
                inventoryService.releaseCommitted(reservationId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, reservationId);
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
