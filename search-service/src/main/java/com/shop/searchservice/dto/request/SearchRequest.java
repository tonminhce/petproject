package com.shop.searchservice.dto.request;

import com.shop.common.core.constants.PageableConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Query-string parameters for {@code GET /api/v1/search} (spec D5).
 * {@code status} is deliberately absent — only ACTIVE docs are indexed (D3).
 */
public record SearchRequest(
    @Size(max = 200)
    String q,

    UUID brandId,

    UUID categoryId,

    BigDecimal minPrice,

    BigDecimal maxPrice,

    BigDecimal minRating,

    /** relevance (default) | price_asc | price_desc | rating_desc | newest */
    @Pattern(regexp = "relevance|price_asc|price_desc|rating_desc|newest")
    String sort,

    /** The largest page that remains inside Elasticsearch result window. */
    @PositiveOrZero
    @Max(MAX_PAGE_NUMBER)
    Integer page,

    @Min(1)
    @Max(PageableConstant.MAX_PAGE_SIZE)
    Integer size
) {
    /** Elasticsearch default result window. */
    public static final int MAX_RESULT_WINDOW = 10_000;
    public static final int MAX_PAGE_NUMBER = MAX_RESULT_WINDOW / PageableConstant.MAX_PAGE_SIZE - 1;

    public SearchRequest {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 20;
        }
    }
}
