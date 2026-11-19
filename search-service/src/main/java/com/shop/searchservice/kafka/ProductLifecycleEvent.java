package com.shop.searchservice.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductLifecycleEvent(
    String eventId,
    String eventType,
    String occurredAt,
    UUID productId,
    String slug,
    String status,
    String title,
    String description,
    UUID brandId,
    String brandName,
    UUID categoryId,
    String categoryName,
    BigDecimal price,
    String imageUrl,
    BigDecimal avgRating,
    Integer ratingCount,
    String updatedAt
) {}
