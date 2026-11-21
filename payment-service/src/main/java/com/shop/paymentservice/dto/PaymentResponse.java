package com.shop.paymentservice.dto;

import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        BigDecimal refundedAmount,
        String refundedCurrency,
        PaymentStatus status,
        PaymentStatus previousStatus,
        String provider,
        String providerIntentId,
        String clientSecret,
        String receiptKey,
        Instant createdAt,
        String refundedReference
) {

    public static PaymentResponse from(Payment payment) {
        return from(payment, null);
    }

    public static PaymentResponse from(Payment payment, String refundedReference) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getRefundedAmount(),
                payment.getRefundedCurrency(),
                payment.getStatus(),
                payment.getPreviousStatus(),
                payment.getProvider(),
                payment.getProviderIntentId(),
                payment.getClientSecret(),
                payment.getReceiptKey(),
                payment.getCreatedAt(),
                refundedReference);
    }
}
