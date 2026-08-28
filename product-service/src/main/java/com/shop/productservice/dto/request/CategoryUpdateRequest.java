package com.shop.productservice.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CategoryUpdateRequest(
    @Size(max = 100) String title,
    @Size(max = 100) String slug,
    @Size(max = 500) String imageUrl,
    UUID parentId
) {}
