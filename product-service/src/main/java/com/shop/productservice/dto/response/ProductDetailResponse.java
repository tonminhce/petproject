package com.shop.productservice.dto.response;

import com.shop.productservice.entity.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductDetailResponse(
    Long id,
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
    Long categoryId,
    String categoryTitle,
    Long brandId,
    String brandName,
    Instant createdAt,
    Instant updatedAt
) {}