package com.shop.productservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface BrandService {

    PageResponse<BrandResponse> findAll(Pageable pageable);

    BrandResponse findById(UUID id);

    BrandResponse create(BrandCreateRequest request);

    BrandResponse update(UUID id, BrandUpdateRequest request);

    void delete(UUID id);
}