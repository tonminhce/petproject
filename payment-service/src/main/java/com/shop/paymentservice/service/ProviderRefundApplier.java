package com.shop.paymentservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.entity.PaymentRefund;
import com.shop.paymentservice.repository.PaymentRefundRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.webhook.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderRefundApplier {

    static final String REFERENCE_PREFIX = "webhook-";
    static final int MAX_REFERENCE_LENGTH = 64;

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentWriter writer;

    @Transactional
    public Payment applyProviderRefund(UUID paymentId, PaymentStatus target, WebhookPayload payload,
            PaymentEvent event) {
        Payment payment = lockPayment(paymentId);
        requireSameCurrency(payload, payment);
        BigDecimal captured = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
        BigDecimal providerRefunded = payload.amount();
        if (providerRefunded == null || providerRefunded.signum() <= 0
                || providerRefunded.compareTo(captured) > 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, providerRefunded, captured);
        }
        if (target == PaymentStatus.REFUNDED && providerRefunded.compareTo(captured) != 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, providerRefunded, captured);
        }
        BigDecimal recorded = recordedRefundedAmount(payment.getId());
        BigDecimal increment = providerRefunded.subtract(recorded);
        if (increment.signum() < 0) {
            log.warn("The provider reports {} refunded for payment {} while {} is already recorded - "
                    + "the recorded amount is kept", providerRefunded, payment.getId(), recorded);
            increment = BigDecimal.ZERO;
        }
        if (increment.signum() > 0) {
            refundRepository.save(PaymentRefund.builder()
                    .paymentId(payment.getId())
                    .refundReference(refundReference(event))
                    .amount(increment)
                    .currency(payment.getCurrency())
                    .provider(payment.getProvider())
                    .providerEventId(event.getProviderEventId())
                    .build());
        }
        BigDecimal refunded = recorded.add(increment);
        PaymentStatus next = refunded.compareTo(captured) == 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        payment.setPreviousStatus(payment.getStatus());
        payment.setStatus(PaymentStateMachine.transition(payment.getStatus(), next));
        payment.setRefundedAmount(refunded);
        payment.setRefundedCurrency(payment.getCurrency());
        writer.completeWithEvent(payment, event, outboxEventType(next));
        log.info("The provider refund of payment {} raised the refunded amount from {} to {}",
                payment.getId(), recorded, refunded);
        return payment;
    }

    private Payment lockPayment(UUID paymentId) {
        try {
            return paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId));
        } catch (PessimisticLockingFailureException | QueryTimeoutException ex) {
            log.warn("The payment row {} could not be locked for the provider refund - the refund is refused",
                    paymentId);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, paymentId);
        }
    }

    private BigDecimal recordedRefundedAmount(UUID paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
                .map(PaymentRefund::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireSameCurrency(WebhookPayload payload, Payment payment) {
        String callbackCurrency = payload.currency();
        if (callbackCurrency == null || callbackCurrency.isBlank()) {
            return;
        }
        String paymentCurrency = payment.getCurrency();
        if (paymentCurrency == null || !callbackCurrency.equalsIgnoreCase(paymentCurrency)) {
            throw BusinessException.of(ErrorCode.PAYMENT_CURRENCY_MISMATCH, callbackCurrency, paymentCurrency);
        }
    }

    private String refundReference(PaymentEvent event) {
        String key = event.getId() != null ? event.getId().toString() : event.getProviderEventId();
        if (key == null) {
            key = "";
        }
        String reference = REFERENCE_PREFIX + key;
        return reference.length() <= MAX_REFERENCE_LENGTH
                ? reference
                : reference.substring(0, MAX_REFERENCE_LENGTH);
    }

    private String outboxEventType(PaymentStatus status) {
        return switch (status) {
            case PARTIALLY_REFUNDED -> "payment.partially_refunded.v1";
            case REFUNDED -> "payment.refunded.v1";
            default -> throw new IllegalStateException("No outbox event type for status " + status);
        };
    }
}
