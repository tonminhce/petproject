package com.shop.searchservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchRequest;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.service.SearchQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.SEARCH)
@RequiredArgsConstructor
public class SearchController {

    private final SearchQueryService searchQueryService;

    @GetMapping
    public ApiResponse<PageResponse<ProductSearchResponse>> search(
            @Valid @ModelAttribute SearchRequest request) {
        return ApiResponse.ok(searchQueryService.search(request));
    }

    @GetMapping("/suggestions")
    public ApiResponse<List<String>> getSuggestions(@RequestParam("q") String query) {
        return ApiResponse.ok(searchQueryService.getSuggestions(query));
    }
}
