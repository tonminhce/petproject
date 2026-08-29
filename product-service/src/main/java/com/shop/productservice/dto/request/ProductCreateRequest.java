package com.shop.productservice.dto.request;

import com.shop.productservice.constant.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductCreateRequest(
    @NotBlank(message = "Title must not be blank")
    @Size(max = 200, message = "Title must be at most 200 characters")
    String title,

    @NotBlank(message = "Slug must not be blank")
    @Size(max = 200, message = "Slug must be at most 200 characters")
    String slug,

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    String description,

    @NotBlank(message = "SKU must not be blank")
    @Size(max = 50, message = "SKU must be at most 50 characters")
    String sku,

    @NotNull(message = "Price unit must not be null")
    @DecimalMin(value = "0.0", message = "Price unit must be at least 0.0")
    BigDecimal priceUnit,

    @NotNull(message = "Quantity must not be null")
    @Min(value = 0, message = "Quantity must be at least 0")
    Integer quantity,

    @NotNull(message = "Status must not be null")
    ProductStatus status,

    @Size(max = 500, message = "Image URL must be at most 500 characters")
    String imageUrl,

    @DecimalMin(value = "0.0", message = "Weight must be at least 0.0")
    BigDecimal weight,

    @Size(max = 50, message = "Dimensions must be at most 50 characters")
    String dimensions,

    UUID categoryId,
    UUID brandId
) {}