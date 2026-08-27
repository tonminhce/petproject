package com.shop.productservice.dto.response;

import java.util.UUID;

public record BrandResponse(
    UUID id,
    String name,
    String slug,
    String logoUrl,
    String description
) {}