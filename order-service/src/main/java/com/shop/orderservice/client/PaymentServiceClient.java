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

/**
 * Payment client for the confirm-path payment-captured gate (order-wiring D1).
 * Mirrors {@link InventoryServiceClient}/{@link PromotionServiceClient}: per-call
 * Authorization header from {@link ServiceTokenProvider} — the payment list
 * endpoint is SERVICE-role.
 * <p>Error posture — fail-closed: ANY failure (non-2xx, timeout, malformed body)
 * maps to {@code Optional.empty()} instead of a thrown exception; the confirm
 * guard turns that into ORD-4012. The raw downstream exception never leaves this
 * class (logged instead). This differs deliberately from {@link TaxServiceClient},
 * which throws SERVICE_UNAVAILABLE/ORDER_TAX_CALCULATION_FAILED: there is no
 * benign "proceed" answer for a payment we could not verify, so the empty result
 * plus the caller-side guard carries the same fail-closed semantics with one
 * single mapped code at the seam.</p>
 */
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

    /**
     * Called by the confirm path before the commit coordinator runs — when false,
     * the payment-captured guard is bypassed entirely (existing behavior).
     */
    public boolean isEnabled() {
        return props.payment().isEnabled();
    }

    /**
     * The captured payment of an order, if there is one.
     *
     * <p>The payment-service owns the answer (its dedicated captured-payment
     * endpoint): the paged list endpoint could not answer it, because a
     * captured payment beyond the first page is invisible to a caller that
     * reads one page - and the gate fails closed, so the order could never be
     * confirmed and was auto-cancelled while the customer had paid.</p>
     */
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
            // no captured payment for this order - a normal answer, not a failure
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
