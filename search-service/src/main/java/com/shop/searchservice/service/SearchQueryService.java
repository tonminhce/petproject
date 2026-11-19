package com.shop.searchservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchRequest;
import com.shop.searchservice.dto.response.ProductSearchResponse;

import java.util.List;

public interface SearchQueryService {

    PageResponse<ProductSearchResponse> search(SearchRequest request);

    List<String> getSuggestions(String query);
}
