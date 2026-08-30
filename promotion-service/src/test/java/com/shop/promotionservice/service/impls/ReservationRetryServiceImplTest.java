package com.shop.promotionservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.service.CampaignReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 6 — retry wrapper (spec §5.1 step 5): mirrors inventory
 * ReservationServiceImpl — 3 attempts, linear backoff 50ms*attempt,
 * exhausted → PRO-7011. Splits from the transactional bean so the proxy
 * boundary is real (no self-invocation) and the loop is unit-testable.
 */
@ExtendWith(MockitoExtension.class)
class ReservationRetryServiceImplTest {

    private static final String CODE = "SAVE10";

    @Mock CampaignReservationService campaignReservationService;
    @InjectMocks ReservationRetryServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private ReserveRequest request() {
        return new ReserveRequest(userId, orderId, new BigDecimal("199.99"));
    }

    private ReservationResponse response() {
        return new ReservationResponse(UUID.randomUUID(), UUID.randomUUID(), CODE,
            new BigDecimal("20.00"), new BigDecimal("179.99"), "PENDING",
            Instant.now().plusSeconds(900));
    }

    @Test
    void reserveWithRetry_retriesOnOptimisticLockFailure() {
        ReserveRequest req = request();
        when(campaignReservationService.reserve(CODE, req))
            .thenThrow(new OptimisticLockingFailureException("conflict"))
            .thenThrow(new OptimisticLockingFailureException("conflict"))
            .thenReturn(response());

        ReservationResponse result = service.reserveWithRetry(CODE, req);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(campaignReservationService, times(3)).reserve(CODE, req);
    }

    @Test
    void reserveWithRetry_throwsVersionConflictAfterMaxRetries() {
        ReserveRequest req = request();
        when(campaignReservationService.reserve(CODE, req))
            .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> service.reserveWithRetry(CODE, req))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7011");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
        verify(campaignReservationService, times(3)).reserve(CODE, req);
    }

    @Test
    void reserveWithRetry_passesThroughSuccessAndBusinessErrors() {
        ReserveRequest req = request();
        ReservationResponse resp = response();
        when(campaignReservationService.reserve(CODE, req)).thenReturn(resp);

        assertThat(service.reserveWithRetry(CODE, req)).isEqualTo(resp);
        verify(campaignReservationService, times(1)).reserve(CODE, req);

        // business rejection is NOT retried — only OptimisticLockingFailureException is
        when(campaignReservationService.reserve(CODE, req))
            .thenThrow(BusinessException.of(com.shop.common.core.exception.ErrorCode.BUDGET_EXHAUSTED, CODE));
        assertThatThrownBy(() -> service.reserveWithRetry(CODE, req))
            .isInstanceOf(BusinessException.class);
        verify(campaignReservationService, times(2)).reserve(CODE, req);
    }
}
