package com.shop.searchservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchRequest;
import com.shop.searchservice.dto.response.ProductSearchResponse;

/**
 * Storefront product query over the {@code products} alias (spec D5):
 * multi_match full-text search with keyword/price/rating filters, the five
 * wire sorts, and from/size pagination capped to the ES result window.
 *
 * <p>Elasticsearch failures — transport I/O and error responses alike (e.g. a
 * missing alias during a degraded provisioning window) — surface as 503
 * SRH-12002, never a raw exception or 500 (spec D6).</p>
 */
public interface SearchQueryService {

    PageResponse<ProductSearchResponse> search(SearchRequest request);
}
