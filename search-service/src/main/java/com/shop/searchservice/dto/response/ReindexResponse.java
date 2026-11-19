package com.shop.searchservice.dto.response;

/**
 * Reindex outcome — the D5 wire shape for
 * {@code POST /api/v1/backoffice/search/reindex} (implemented in Task 5).
 */
public record ReindexResponse(
    long indexed,
    String indexName,
    long tookMs
) {
}
