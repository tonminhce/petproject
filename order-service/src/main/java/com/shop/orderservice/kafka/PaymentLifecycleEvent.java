package com.shop.orderservice.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment lifecycle envelope matching topic {@code shop.payment.lifecycle.v1}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentLifecycleEvent(
        String eventId,
        String eventType,
        String occurredAt,
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String status,
        String previousStatus
) {
}
