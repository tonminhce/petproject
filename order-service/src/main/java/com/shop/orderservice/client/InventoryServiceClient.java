package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.dto.internal.ReservationResponse;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * ⚠️ P0-5 — Unwraps ApiResponse envelope.
 * ⚠️ P1-3 — Per-call Authorization header từ `ServiceTokenProvider` (Task 2). Chỉ client
 * này cần token (product GET public; tax/promotion disabled MVP).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceClient {

    @Qualifier("inventoryRestClient")
    private final RestClient restClient;
    private final ServiceTokenProvider serviceTokenProvider;   // P0-7 — SERVICE-role token

    private static final ParameterizedTypeReference<ApiResponse<ReservationResponse>> RESERVE_RESPONSE =
        new ParameterizedTypeReference<>() {};

    public UUID reserve(UUID productId, ReserveRequest request) {
        try {
            ApiResponse<ReservationResponse> resp = restClient.post()
                .uri("/api/v1/inventory/{productId}/reserve", productId)
                // ⚠️ P0-7 — reserve yêu cầu SERVICE role (inventory §4.2). Không có header = 401.
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .body(request)
                .retrieve()
                .body(RESERVE_RESPONSE);
            return resp.data().reservationId();
        } catch (HttpClientErrorException ex) {
            log.warn("Inventory reserve failed for product {}: {}", productId, ex.getMessage());
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                throw new StockReservationFailedException(productId, ex);
            }
            throw BusinessException.of(ErrorCode.INTERNAL_SERVER_ERROR, "inventory");
        }
    }

    public void release(UUID reservationId) {
        try {
            restClient.post()
                .uri("/api/v1/inventory/reservations/{id}/release", reservationId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .toBodilessEntity();
        } catch (Exception ex) {
            log.error("Failed to release reservation {}", reservationId, ex);
            // DO NOT throw — compensation failures are best-effort, logged for ops review
        }
    }
}
