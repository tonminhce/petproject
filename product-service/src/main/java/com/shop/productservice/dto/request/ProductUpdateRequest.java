package com.shop.productservice.dto.request;

import com.shop.productservice.entity.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductUpdateRequest(
    @Size(max = 200)             String title,
    @Size(max = 200)             String slug,
    @Size(max = 2000)            String description,
    @Size(max = 50)              String sku,
    @DecimalMin("0.0")           BigDecimal priceUnit,
    @Min(0)                      Integer quantity,
    ProductStatus status,
    @Size(max = 500)             String imageUrl,
    @DecimalMin("0.0")           BigDecimal weight,
    @Size(max = 50)              String dimensions,
    UUID categoryId,
    UUID brandId
) {}