package com.shop.searchservice.dto.request;

/**
 * Optional body for {@code POST /api/v1/backoffice/search/reindex} (spec D5).
 * Absent body or absent flag → full reindex; {@code {"dryRun":true}} counts
 * only.
 */
public record ReindexRequest(Boolean dryRun) {
}
