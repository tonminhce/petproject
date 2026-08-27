package com.shop.productservice.dto.request;

public record CategoryUpdateRequest(
    String title,
    String slug,
    String imageUrl,
    Long parentId
) {}