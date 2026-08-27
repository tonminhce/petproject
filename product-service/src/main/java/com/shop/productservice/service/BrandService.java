package com.shop.productservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import org.springframework.data.domain.Pageable;

public interface BrandService {

    PageResponse<BrandResponse> findAll(Pageable pageable);

    BrandResponse findById(Long id);

    BrandResponse create(BrandCreateRequest request);

    BrandResponse update(Long id, BrandUpdateRequest request);

    void delete(Long id);
}