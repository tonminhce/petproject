package com.shop.productservice.dto.response;

public record BrandResponse(
    Long id,
    String name,
    String slug,
    String logoUrl,
    String description
) {}