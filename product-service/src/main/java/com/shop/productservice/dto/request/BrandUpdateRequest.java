package com.shop.productservice.dto.request;

import jakarta.validation.constraints.Size;

public record BrandUpdateRequest(
    @Size(max = 100)            String name,
    @Size(max = 100)            String slug,
    @Size(max = 500)            String logoUrl,
    @Size(max = 1000)           String description
) {}
