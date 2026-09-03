package com.shop.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound Kafka event: order lifecycle transitions emitted by order-service
 * over {@code shop.order.lifecycle.v1} (R1 single-encoded UTF-8 JSON).
 *
 * <p>H26 / R1 — this is a Java {@code record}: immutable, canonical-ctor
 * construction, accessor API. No Lombok on top (R1 forbids {@code @Builder}
 * / {@code @Getter} / {@code @Setter} on records): the canonical
 * constructor is the construction API, and Jackson + records binds through
 * {@code @JsonCreator}-discovered canonical-ctor binding — not the
 * Lombok-generated builder. References:
 * <a href="https://openjdk.org/jeps/395">JEP 395</a> (Java 16 records) and
 * <a href="https://projectlombok.org/features/Builder">Lombok @Builder docs</a>.
 */
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
        Instant transitionedAt,
        Instant cancelledAt,
        Boolean refunded,
        List<Map<String, Object>> items) {
}
