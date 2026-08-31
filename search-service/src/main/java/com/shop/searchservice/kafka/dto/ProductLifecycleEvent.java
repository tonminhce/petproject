package com.shop.searchservice.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Product lifecycle envelope — the consumer's input contract is spec D2
 * verbatim (17 fields, FULL catalog snapshot). Tolerates unknown fields so
 * additive payload enrichment from product-service never breaks ingestion.
 */
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
