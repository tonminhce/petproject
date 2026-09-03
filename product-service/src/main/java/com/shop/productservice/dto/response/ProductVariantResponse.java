package com.shop.productservice.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductVariantResponse(
    UUID id,
    UUID productId,
    String sku,
    String title,
    BigDecimal price,
    Integer quantity,
    String attributes,
    String imageUrl,
    Instant createdAt,
    Instant updatedAt
) {}
