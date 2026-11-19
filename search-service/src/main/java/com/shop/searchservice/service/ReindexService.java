package com.shop.searchservice.service;

import com.shop.searchservice.dto.response.ReindexResponse;

/**
 * ADMIN-triggered full reindex (spec D5): streams ALL ACTIVE products from
 * product-service page-by-page, bulk-indexes them into a fresh
 * {@code products-v{n+1}} index, then atomically swaps the {@code products}
 * alias and deletes every superseded {@code products-v*} index.
 */
public interface ReindexService {

    /**
     * Runs a full reindex, or with {@code dryRun=true} only counts the ACTIVE
     * source rows (no index creation, no writes, no alias change).
     *
     * @throws BusinessException SRH-12001 (409) if a reindex is already running
     * @throws BusinessException SRH-12002 (503) if the source or Elasticsearch fails
     */
    ReindexResponse reindex(boolean dryRun);
}
