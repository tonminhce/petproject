package com.shop.productservice.dto.response;

public record CategoryResponse(
    Long id,
    String title,
    String slug,
    String imageUrl,
    Long parentId
) {}