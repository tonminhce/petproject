package com.shop.productservice.dto.request;

public record BrandUpdateRequest(
    String name,
    String slug,
    String logoUrl,
    String description
) {}