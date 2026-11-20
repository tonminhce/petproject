package com.shop.orderservice.client;

import com.shop.common.core.constants.PlatformCurrency;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PaymentStatusSnapshot;
import com.shop.orderservice.dto.internal.RefundRequest;
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

import java.math.BigDecimal;
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
        return refund(orderId, null, null);
    }

    @CircuitBreaker(name = "paymentService")
    public boolean refundByOrderId(UUID orderId, BigDecimal amount, String currency) {
        return refund(orderId, amount, currency);
    }

    private boolean refund(UUID orderId, BigDecimal amount, String currency) {
        if (!isEnabled()) {
            return true;
        }
        Optional<PaymentStatusSnapshot> captured = findCapturedByOrderId(orderId);
        if (captured.isEmpty()) {
            return false;
        }
        PaymentStatusSnapshot payment = captured.get();
        BigDecimal refundAmount = amount != null ? amount : payment.amount();
        String refundCurrency = currency != null ? currency : payment.currency();
        if (amount != null) {
            requireWithinRemainingRefundableAmount(payment, refundAmount);
            requireRefundCurrencyMatchesPayment(payment, refundCurrency);
        }
        UUID paymentId = payment.id();
        try {
            RestClient.RequestBodySpec request = restClient.post()
                .uri("/api/v1/payments/{id}/refund", paymentId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken());
            if (refundAmount != null) {
                request.body(new RefundRequest(refundAmount, refundCurrency));
            }
            request.retrieve().toBodilessEntity();
            log.info("Successfully triggered refund of {} {} for payment {} on order {}",
                refundAmount, refundCurrency, paymentId, orderId);
            return true;
        } catch (RestClientException ex) {
            log.error("Failed to trigger refund for payment {} on order {}", paymentId, orderId, ex);
            return false;
        }
    }

    private void requireWithinRemainingRefundableAmount(PaymentStatusSnapshot payment, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, amount);
        }
        BigDecimal alreadyRefunded = payment.refundedAmount() != null
            ? payment.refundedAmount()
            : BigDecimal.ZERO;
        BigDecimal remaining = payment.amount() == null
            ? null
            : payment.amount().subtract(alreadyRefunded);
        if (remaining == null || amount.compareTo(remaining) > 0) {
            log.warn("Rejected refund of {} for payment {}: only {} of the captured amount is still refundable",
                amount, payment.id(), remaining);
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, amount, remaining);
        }
    }

    private void requireRefundCurrencyMatchesPayment(PaymentStatusSnapshot payment, String currency) {
        String paymentCurrency = PlatformCurrency.normalize(payment.currency());
        String refundCurrency = PlatformCurrency.normalize(currency);
        if (paymentCurrency == null || refundCurrency == null || !paymentCurrency.equals(refundCurrency)) {
            log.warn("Rejected refund in {} for payment {}: the payment is in {}",
                refundCurrency, payment.id(), paymentCurrency);
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_MISMATCH, refundCurrency, paymentCurrency);
        }
    }
}
