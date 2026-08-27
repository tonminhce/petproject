package com.shop.productservice.dto.request;

import com.shop.productservice.entity.ProductStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductUpdateRequest(
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
    UUID categoryId,
    UUID brandId
) {}