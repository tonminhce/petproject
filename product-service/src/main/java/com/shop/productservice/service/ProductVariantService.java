package com.shop.productservice.service;

import com.shop.productservice.dto.request.ProductVariantCreateRequest;
import com.shop.productservice.dto.request.ProductVariantUpdateRequest;
import com.shop.productservice.dto.response.ProductVariantResponse;

import java.util.List;
import java.util.UUID;

public interface ProductVariantService {

    List<ProductVariantResponse> findByProductId(UUID productId);

    ProductVariantResponse findById(UUID productId, UUID variantId);

    ProductVariantResponse create(UUID productId, ProductVariantCreateRequest request);

    ProductVariantResponse update(UUID productId, UUID variantId, ProductVariantUpdateRequest request);

    void delete(UUID productId, UUID variantId);
}
