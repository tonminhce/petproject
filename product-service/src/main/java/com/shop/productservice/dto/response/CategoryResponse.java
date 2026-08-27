package com.shop.productservice.dto.response;

import java.util.UUID;

public record CategoryResponse(
    UUID id,
    String title,
    String slug,
    String imageUrl,
    UUID parentId
) {}