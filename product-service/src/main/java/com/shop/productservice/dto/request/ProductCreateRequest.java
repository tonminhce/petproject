package com.shop.productservice.dto.request;

import com.shop.productservice.entity.ProductStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductCreateRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 200) String slug,
    @Size(max = 2000)           String description,
    @NotBlank @Size(max = 50)  String sku,
    @NotNull  @DecimalMin("0.0") BigDecimal priceUnit,
    @NotNull  @Min(0)            Integer quantity,
    @NotNull                    ProductStatus status,
    @Size(max = 500)             String imageUrl,
    @DecimalMin("0.0")           BigDecimal weight,
    @Size(max = 50)              String dimensions,
    UUID categoryId,
    UUID brandId
) {}