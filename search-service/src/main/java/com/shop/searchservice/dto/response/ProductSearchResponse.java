package com.shop.searchservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSearchResponse(
    UUID id,
    String title,
    String brandName,
    String categoryName,
    String slug,
    String imageUrl,
    BigDecimal price,
    BigDecimal avgRating,
    Integer ratingCount
) {
}
