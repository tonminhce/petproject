package com.shop.productservice.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

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
