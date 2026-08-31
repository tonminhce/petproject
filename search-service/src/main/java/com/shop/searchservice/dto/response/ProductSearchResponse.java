package com.shop.searchservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Query result item — the D5 wire shape. Description is intentionally not
 * echoed back (storefront list surface, not a detail view).
 */
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
