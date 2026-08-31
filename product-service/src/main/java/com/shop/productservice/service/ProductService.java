package com.shop.productservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ProductService {

    PageResponse<ProductSummaryResponse> findAll(ProductFilter filter, Pageable pageable);

    /**
     * Paged list with the FULL detail mapping (brand/category resolved) — the
     * backoffice reindex source (F2 STEP 0). Relations are fetch-joined to
     * avoid N+1 lazy loads across the page.
     */
    PageResponse<ProductDetailResponse> findAllDetail(ProductFilter filter, Pageable pageable);

    ProductDetailResponse findById(UUID id);

    ProductDetailResponse findBySlug(String slug);

    ProductDetailResponse create(ProductCreateRequest request);

    ProductDetailResponse update(UUID id, ProductUpdateRequest request);

    void delete(UUID id);
}