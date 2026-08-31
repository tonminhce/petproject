package com.shop.productservice.dto.response;

import com.shop.productservice.constant.ProductStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSummaryResponse(
    UUID id,
    String title,
    String slug,
    String sku,
    BigDecimal priceUnit,
    Integer quantity,
    ProductStatus status,
    String imageUrl,
    BigDecimal avgRating,
    Integer ratingCount
) {}