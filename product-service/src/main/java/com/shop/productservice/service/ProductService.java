package com.shop.productservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import org.springframework.data.domain.Pageable;

public interface ProductService {

    PageResponse<ProductSummaryResponse> findAll(ProductFilter filter, Pageable pageable);

    ProductDetailResponse findById(Long id);

    ProductDetailResponse findBySlug(String slug);

    ProductDetailResponse create(ProductCreateRequest request);

    ProductDetailResponse update(Long id, ProductUpdateRequest request);

    void delete(Long id);
}