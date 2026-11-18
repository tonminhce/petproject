package com.shop.promotionservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.service.CampaignReservationService;
import com.shop.promotionservice.service.ReservationRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ReservationRetryServiceImpl implements ReservationRetryService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 50L;

    private final CampaignReservationService campaignReservationService;

    @Override
    public ReservationResponse reserveWithRetry(String code, ReserveRequest request) {
        return withRetry(code, () -> campaignReservationService.reserve(code, request));
    }

    @Override
    public void commitWithRetry(UUID reservationId) {
        withRetry(reservationId, () -> {
            campaignReservationService.commit(reservationId);
            return null;
        });
    }

    @Override
    public void releaseWithRetry(UUID reservationId) {
        withRetry(reservationId, () -> {
            campaignReservationService.release(reservationId);
            return null;
        });
    }

    @Override
    public void releaseCommittedWithRetry(UUID reservationId) {
        withRetry(reservationId, () -> {
            campaignReservationService.releaseCommitted(reservationId);
            return null;
        });
    }

    @Override
    public ReservationResponse getStateWithRetry(UUID reservationId) {
        return withRetry(reservationId, () -> campaignReservationService.getState(reservationId));
    }

    private <T> T withRetry(Object key, Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_VERSION_CONFLICT, key);
                }

                if (!sleep(BACKOFF_BASE_MS * attempt)) {
                    Thread.currentThread().interrupt();
                    throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_VERSION_CONFLICT, key);
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
