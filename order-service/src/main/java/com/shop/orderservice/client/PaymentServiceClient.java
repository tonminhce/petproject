package com.shop.orderservice.client;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PaymentStatusSnapshot;
import com.shop.orderservice.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceClient {

    private static final String STATUS_CAPTURED = "CAPTURED";

    private static final ParameterizedTypeReference<ApiResponse<PaymentStatusSnapshot>> SNAPSHOT_TYPE =
        new ParameterizedTypeReference<>() {};

    private final ShopServicesProperties props;

    @Qualifier("paymentRestClient")
    private final RestClient restClient;

    private final ServiceTokenProvider serviceTokenProvider;

    public boolean isEnabled() {
        return props.payment().isEnabled();
    }

    @CircuitBreaker(name = "paymentService")
    public Optional<PaymentStatusSnapshot> findCapturedByOrderId(UUID orderId) {
        try {
            ApiResponse<PaymentStatusSnapshot> resp = restClient.get()
                .uri("/api/v1/payments/by-order/{orderId}/captured", orderId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(SNAPSHOT_TYPE);
            if (resp == null || resp.data() == null) {
                return Optional.empty();
            }
            PaymentStatusSnapshot payment = resp.data();
            return STATUS_CAPTURED.equals(payment.status()) ? Optional.of(payment) : Optional.empty();
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            log.error("Payment status lookup failed for order {} — failing closed", orderId, ex);
            return Optional.empty();
        }
    }

    @CircuitBreaker(name = "paymentService")
    public boolean refundByOrderId(UUID orderId) {
        if (!isEnabled()) {
            return true;
        }
        Optional<PaymentStatusSnapshot> captured = findCapturedByOrderId(orderId);
        if (captured.isEmpty()) {
            return false;
        }
        UUID paymentId = captured.get().id();
        try {
            restClient.post()
                .uri("/api/v1/payments/{id}/refund", paymentId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .toBodilessEntity();
            log.info("Successfully triggered refund for payment {} on order {}", paymentId, orderId);
            return true;
        } catch (RestClientException ex) {
            log.error("Failed to trigger refund for payment {} on order {}", paymentId, orderId, ex);
            return false;
        }
    }
}
