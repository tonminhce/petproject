package com.shop.searchservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Query-string parameters for {@code GET /api/v1/search} (spec D5).
 * {@code status} is deliberately absent — only ACTIVE docs are indexed (D3).
 */
@Data
public class SearchParams {

    @Size(max = 200)
    private String q;

    private UUID brandId;

    private UUID categoryId;

    private BigDecimal minPrice;

    private BigDecimal maxPrice;

    private BigDecimal minRating;

    /** relevance (default) | price_asc | price_desc | rating_desc | newest */
    @Pattern(regexp = "relevance|price_asc|price_desc|rating_desc|newest")
    private String sort;

    @PositiveOrZero
    private int page = 0;

    @Min(1)
    @Max(200)
    private int size = 20;
}
