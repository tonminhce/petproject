package com.shop.productservice.dto.response;

import com.shop.productservice.constant.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductDetailResponse(
    UUID id,
    String title,
    String slug,
    String description,
    String sku,
    BigDecimal priceUnit,
    Integer quantity,
    ProductStatus status,
    String imageUrl,
    BigDecimal weight,
    String dimensions,
    BigDecimal avgRating,
    Integer ratingCount,
    UUID categoryId,
    String categoryTitle,
    UUID brandId,
    String brandName,
    Instant createdAt,
    Instant updatedAt
) {}