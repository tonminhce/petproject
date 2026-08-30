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

/**
 * Wraps {@link CampaignReservationService} with a manual retry loop for
 * {@link OptimisticLockingFailureException} (spec §5.1 step 5). A retry
 * re-reads the campaign (fresh @Version, post-version-touch) and re-validates
 * — safe because every gate re-runs against the winner's committed state.
 * Deliberately NOT transactional: each attempt gets its own tx boundary from
 * the delegate.
 */
@Service
@RequiredArgsConstructor
public class ReservationRetryServiceImpl implements ReservationRetryService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 50L;

    private final CampaignReservationService campaignReservationService;

    @Override
    public ReservationResponse reserveWithRetry(String code, ReserveRequest request) {
        int attempt = 0;
        while (true) {
            try {
                return campaignReservationService.reserve(code, request);
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_VERSION_CONFLICT, code);
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
