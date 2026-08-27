package com.shop.productservice.dto.request;

import java.util.UUID;

public record CategoryUpdateRequest(
    String title,
    String slug,
    String imageUrl,
    UUID parentId
) {}