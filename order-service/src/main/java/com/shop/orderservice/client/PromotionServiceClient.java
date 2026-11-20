package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PromotionReserveRequest;
import com.shop.orderservice.dto.internal.PromotionReserveResponse;
import com.shop.orderservice.dto.internal.ReservationStateResponse;
import com.shop.orderservice.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionServiceClient {

    private final ShopServicesProperties props;

    @Qualifier("promotionRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    private static final ParameterizedTypeReference<ApiResponse<PromotionReserveResponse>> RESERVE_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<ReservationStateResponse>> STATE_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> ERROR_BODY =
        new ParameterizedTypeReference<>() {};

    public boolean isEnabled() {
        return props.promotion().isEnabled();
    }

    @CircuitBreaker(name = "promotionService")
    public PromotionReserveResponse reserve(PromotionReserveRequest request) {
        if (!props.promotion().isEnabled()) {
            return new PromotionReserveResponse(null, BigDecimal.ZERO, request.orderAmount(), request.currency());
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
