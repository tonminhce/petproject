package com.shop.productservice.dto.response;

import java.util.List;

public record CategoryTreeResponse(
    Long id,
    String title,
    String slug,
    String imageUrl,
    Long parentId,
    List<CategoryTreeResponse> children
) {}