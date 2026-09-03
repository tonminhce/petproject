package com.shop.productservice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductVariantCreateRequest(
    @NotBlank
    @Size(max = 50)
    String sku,

    @NotBlank
    @Size(max = 150)
    String title,

    @NotNull
    @DecimalMin("0.00")
    BigDecimal price,

    @NotNull
    @Min(0)
    Integer quantity,

    @Size(max = 1000)
    String attributes,

    @Size(max = 500)
    String imageUrl
) {}
