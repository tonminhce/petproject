package com.shop.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrandCreateRequest(
    @NotBlank @Size(max = 100)  String name,
    @NotBlank @Size(max = 100)  String slug,
    @Size(max = 500)            String logoUrl,
    @Size(max = 1000)           String description
) {}