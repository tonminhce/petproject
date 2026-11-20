package com.shop.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLifecycleEvent(
        String eventId,
        String eventType,
        String occurredAt,
        UUID orderId,
        UUID userId,
        String status,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal total,
        String currency,
        Instant transitionedAt,
        Instant cancelledAt,
        Boolean refunded,
        List<Map<String, Object>> items) {
}
