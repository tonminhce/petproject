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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceClient {

    private static final String STATUS_CAPTURED = "CAPTURED";
    private static final String STATUS_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";

    private static final Set<String> REFUNDABLE_STATUSES =
            Set.of(STATUS_CAPTURED, STATUS_PARTIALLY_REFUNDED);

    private static final ParameterizedTypeReference<ApiResponse<PaymentStatusSnapshot>> SNAPSHOT_TYPE =
        new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> ERROR_BODY =
        new ParameterizedTypeReference<>() {};

    private final ShopServicesProperties props;

    @Qualifier("paymentRestClient")
    private final RestClient restClient;

    @Qualifier("paymentRefundRestClient")
    private final RestClient refundRestClient;

    private final ServiceTokenProvider serviceTokenProvider;

    public boolean isEnabled() {
        return props.payment().isEnabled();
    }

    @CircuitBreaker(name = "paymentService")
    public Optional<PaymentStatusSnapshot> findCapturedByOrderId(UUID orderId) {
        return lookupPayment(orderId, "/api/v1/payments/by-order/{orderId}/captured",
                Set.of(STATUS_CAPTURED));
    }

    @CircuitBreaker(name = "paymentService")
    public Optional<PaymentStatusSnapshot> findRefundableByOrderId(UUID orderId, UUID refundReference) {
        try {
            ApiResponse<PaymentStatusSnapshot> resp = restClient.get()
                .uri(refundableUri(orderId, refundReference))
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(SNAPSHOT_TYPE);
            if (resp == null || resp.data() == null) {
                return Optional.empty();
            }
            PaymentStatusSnapshot payment = resp.data();
            if (REFUNDABLE_STATUSES.contains(payment.status())
                    || carriesConfirmedRefund(payment, refundReference)) {
                return Optional.of(payment);
            }
            return Optional.empty();
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException ex) {
            log.warn("Payment service refused the refundable lookup of order {}: {}",
                    orderId, ex.getMessage());
            throw remoteBusinessException(ex);
        } catch (RestClientException ex) {
            log.error("Payment status lookup failed for order {} - the refund is refused", orderId, ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment");
        }
    }

    private Optional<PaymentStatusSnapshot> lookupPayment(UUID orderId, String uriTemplate,
            Set<String> acceptedStatuses) {
        try {
            ApiResponse<PaymentStatusSnapshot> resp = restClient.get()
                .uri(uriTemplate, orderId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .body(SNAPSHOT_TYPE);
            if (resp == null || resp.data() == null) {
                return Optional.empty();
            }
            PaymentStatusSnapshot payment = resp.data();
            return acceptedStatuses.contains(payment.status()) ? Optional.of(payment) : Optional.empty();
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            log.error("Payment status lookup failed for order {} — failing closed", orderId, ex);
            return Optional.empty();
        }
    }

    private URI refundableUri(UUID orderId, UUID refundReference) {
        UriBuilder builder = UriComponentsBuilder.fromPath("/api/v1/payments/by-order/{orderId}/refundable");
        if (refundReference != null) {
            builder.queryParam("refundReference", refundReference);
        }
        return builder.build(orderId);
    }

    @CircuitBreaker(name = "paymentService")
    public RefundOutcome refundByOrderId(UUID orderId) {
        return refund(orderId, null, null, orderId);
    }

    @CircuitBreaker(name = "paymentService")
    public RefundOutcome refundByOrderId(UUID orderId, BigDecimal amount, String currency, UUID refundReference) {
        return refund(orderId, amount, currency, refundReference);
    }

    private RefundOutcome refund(UUID orderId, BigDecimal amount, String currency, UUID refundReference) {
        if (!isEnabled()) {
            return RefundOutcome.REFUNDED;
        }
        Optional<PaymentStatusSnapshot> refundable = findRefundableByOrderId(orderId, refundReference);
        if (refundable.isEmpty()) {
            return RefundOutcome.NO_PAYMENT;
        }
        PaymentStatusSnapshot payment = refundable.get();
        if (carriesConfirmedRefund(payment, refundReference)) {
            log.info("The payment {} of order {} already carries the confirmed refund {} - no money moves again",
                    payment.id(), orderId, refundReference);
            return RefundOutcome.ALREADY_REFUNDED;
        }
        BigDecimal refundAmount = amount != null ? amount : remainingRefundableAmount(payment);
        String refundCurrency = currency != null ? currency : payment.currency();
        requireWithinRemainingRefundableAmount(payment, refundAmount);
        requireRefundCurrencyMatchesPayment(payment, refundCurrency);
        UUID paymentId = payment.id();
        try {
            refundRestClient.post()
                .uri("/api/v1/payments/{id}/refund", paymentId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .body(new RefundRequest(refundAmount, refundCurrency, refundReference))
                .retrieve()
                .toBodilessEntity();
            log.info("Successfully triggered refund of {} {} for payment {} on order {}",
                refundAmount, refundCurrency, paymentId, orderId);
            return RefundOutcome.REFUNDED;
        } catch (HttpClientErrorException ex) {
            log.warn("Payment service refused the refund of payment {} on order {}: {}",
                paymentId, orderId, ex.getMessage());
            throw remoteBusinessException(ex);
        } catch (HttpServerErrorException ex) {
            log.error("Payment service failed the refund of payment {} on order {}", paymentId, orderId, ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment");
        } catch (RestClientException ex) {
            log.error("Failed to trigger refund for payment {} on order {}", paymentId, orderId, ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment");
        }
    }

    private boolean carriesConfirmedRefund(PaymentStatusSnapshot payment, UUID refundReference) {
        return refundReference != null
                && payment.refundedReference() != null
                && payment.refundedReference().equals(refundReference.toString());
    }

    private BigDecimal remainingRefundableAmount(PaymentStatusSnapshot payment) {
        if (payment.amount() == null) {
            return null;
        }
        BigDecimal alreadyRefunded = payment.refundedAmount() != null
            ? payment.refundedAmount()
            : BigDecimal.ZERO;
        return payment.amount().subtract(alreadyRefunded);
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
            log.debug("Could not parse the payment error body", parseFailure);
        }
        return BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "payment");
    }
}
