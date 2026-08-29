package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PromotionReserveRequest;
import com.shop.orderservice.dto.internal.PromotionReserveResponse;
import com.shop.orderservice.dto.internal.ReservationStateResponse;
import com.shop.orderservice.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Promotion client for the confirm-hardening reserve/commit flow (hardening §2).
 * Mirrors {@link InventoryServiceClient}: per-call Authorization header from
 * {@link ServiceTokenProvider} — promotion endpoints are SERVICE-role.
 *
 * <p>Error mapping: {@code reserve()} 4xx → ORDER_PROMOTION_INVALID (user-facing
 * coupon problem); {@code commit}/{@code release}/{@code releaseCommitted} 4xx →
 * rethrow the remote {@code ApiResponse.code} as a matching {@link ErrorCode}
 * when it resolves (RESERVATION_* codes must stay distinguishable for the
 * coordinator), otherwise SERVICE_UNAVAILABLE. 5xx always fails closed with
 * SERVICE_UNAVAILABLE.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionServiceClient {

    private final ShopServicesProperties props;

    @Qualifier("promotionRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;   // SERVICE-role token

    private static final ParameterizedTypeReference<ApiResponse<PromotionReserveResponse>> RESERVE_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<ReservationStateResponse>> STATE_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> ERROR_BODY =
        new ParameterizedTypeReference<>() {};

    /**
     * Called by {@link com.shop.orderservice.service.impls.PricingServiceImpl} BEFORE invoking
     * {@code reserve()} — if false and user provided a couponCode, we reject with 400 (P1-5 fix).
     */
    public boolean isEnabled() {
        return props.promotion().isEnabled();
    }

    public PromotionReserveResponse reserve(PromotionReserveRequest request) {
        if (!props.promotion().isEnabled()) {
            // Caller (PricingService) should have already rejected user-supplied coupon when
            // promotion is disabled — see PricingServiceImpl.calculate for the check.
            // Defensive fallback: no discount.
            return new PromotionReserveResponse(null, java.math.BigDecimal.ZERO, request.orderAmount());
        }
        try {
            ApiResponse<PromotionReserveResponse> resp = restClient.post()
                .uri("/api/v1/promotions/{code}/reserve", request.code())
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .body(request)
                .retrieve()
                .body(RESERVE_RESPONSE);
            return resp.data();
        } catch (HttpClientErrorException ex) {
            log.warn("Promotion reserve rejected for {}: {}", request.code(), ex.getMessage());
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, request.code());
        } catch (HttpServerErrorException ex) {
            log.error("Promotion service 5xx — failing closed", ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
        }
    }

    public void commit(UUID reservationId) {
        lifecycleCall("/api/v1/promotions/reservations/{id}/commit", reservationId, "commit");
    }

    public void release(UUID reservationId) {
        lifecycleCall("/api/v1/promotions/reservations/{id}/release", reservationId, "release");
    }

    public void releaseCommitted(UUID reservationId) {
        lifecycleCall("/api/v1/promotions/reservations/{id}/release-committed", reservationId, "release-committed");
    }

    public ReservationStateResponse getReservationState(UUID reservationId) {
        try {
            ApiResponse<ReservationStateResponse> resp = restClient.get()
                .uri("/api/v1/promotions/reservations/{id}/state", reservationId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(STATE_RESPONSE);
            return resp.data();
        } catch (HttpClientErrorException ex) {
            log.warn("Promotion state lookup failed for reservation {}: {}", reservationId, ex.getMessage());
            throw remoteBusinessException(ex);
        } catch (HttpServerErrorException ex) {
            log.error("Promotion service 5xx on state lookup — failing closed", ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
        }
    }

    private void lifecycleCall(String pathTemplate, UUID reservationId, String op) {
        try {
            restClient.post()
                .uri(pathTemplate, reservationId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            log.warn("Promotion reservation {} failed for {}: {}", op, reservationId, ex.getMessage());
            throw remoteBusinessException(ex);
        } catch (HttpServerErrorException ex) {
            log.error("Promotion service 5xx on {} — failing closed", op, ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
        }
    }

    /**
     * Rethrows a remote 4xx as a {@link BusinessException} carrying the remote
     * {@code ApiResponse.code} when it resolves to a known {@link ErrorCode}
     * (e.g. RESERVATION_* codes — the coordinator must be able to distinguish
     * NOT_FOUND from INVALID_STATE). Falls back to SERVICE_UNAVAILABLE when the
     * body or the code cannot be parsed.
     */
    private BusinessException remoteBusinessException(HttpClientErrorException ex) {
        try {
            ApiResponse<Void> error = ex.getResponseBodyAs(ERROR_BODY);
            if (error != null && error.code() != null) {
                for (ErrorCode candidate : ErrorCode.values()) {
                    if (candidate.getCode().equals(error.code())) {
                        return BusinessException.of(candidate);
                    }
                }
            }
        } catch (Exception parseFailure) {
            log.debug("Could not parse promotion error body", parseFailure);
        }
        return BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
    }
}
