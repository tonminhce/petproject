package com.shop.productservice.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Flattened rating lifecycle envelope — the consumer's input contract is spec
 * D4 verbatim (13 fields). The payload CARRIES the avgRating/ratingCount
 * snapshot recomputed in rating-service's transaction, so this consumer is a
 * dumb idempotent copy-reader: no recompute, no rating-service client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RatingLifecycleEvent(
    String eventId,
    String eventType,
    String occurredAt,
    UUID ratingId,
    UUID productId,
    UUID userId,
    int rating,
    String comment,
    boolean verified,
    String action,
    boolean visible,
    BigDecimal avgRating,
    int ratingCount
) {}
