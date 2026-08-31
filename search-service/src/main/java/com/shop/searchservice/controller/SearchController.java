package com.shop.searchservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchParams;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.service.SearchQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Storefront product search (spec D5). Deliberately NOT annotated with
 * {@code @PreAuthorize} (fleet precedent P2-6, {@code OrderController} /
 * {@code StorefrontRatingController}): Keycloak users may lack an explicit
 * USER realm role, which would surface as an unhelpful 403. The fleet filter
 * chain still enforces authentication on this edge-routed path — anonymous
 * callers get 401.
 */
@RestController
@RequestMapping(ApiPaths.SEARCH)
@RequiredArgsConstructor
public class SearchController {

    private final SearchQueryService searchQueryService;

    @GetMapping
    public ApiResponse<PageResponse<ProductSearchResponse>> search(
            @Valid @ModelAttribute SearchParams params) {
        return ApiResponse.ok(searchQueryService.search(params));
    }
}
