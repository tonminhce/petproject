package com.shop.productservice.dto.response;

import com.shop.productservice.entity.ProductStatus;

import java.math.BigDecimal;

public record ProductSummaryResponse(
    Long id,
    String title,
    String slug,
    String sku,
    BigDecimal priceUnit,
    Integer quantity,
    ProductStatus status,
    String imageUrl
) {}