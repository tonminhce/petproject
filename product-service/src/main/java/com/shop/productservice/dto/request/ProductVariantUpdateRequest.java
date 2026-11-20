package com.shop.productservice.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@JsonIgnoreProperties("currency")
public record ProductVariantUpdateRequest(
    @Size(max = 50)
    String sku,

    @Size(max = 150)
    String title,

    @DecimalMin("0.00")
    BigDecimal price,

    @Min(0)
    Integer quantity,

    @Size(max = 1000)
    String attributes,

    @Size(max = 500)
    String imageUrl
) {}
