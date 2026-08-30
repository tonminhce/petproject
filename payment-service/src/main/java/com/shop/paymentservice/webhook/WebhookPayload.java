package com.shop.paymentservice.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public record WebhookPayload(
        String eventId,
        String eventType,
        String paymentId,
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String providerEventId
) {
}
