package com.shop.productservice.dto.response;

import java.util.List;
import java.util.UUID;

public record CategoryTreeResponse(
    UUID id,
    String title,
    String slug,
    String imageUrl,
    UUID parentId,
    List<CategoryTreeResponse> children
) {}