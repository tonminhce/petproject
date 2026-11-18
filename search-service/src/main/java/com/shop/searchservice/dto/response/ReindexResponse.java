package com.shop.searchservice.dto.response;

public record ReindexResponse(
    long indexed,
    String indexName,
    long tookMs
) {
}
