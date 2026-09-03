package com.shop.productservice.controller;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.productservice.dto.response.ProductVariantResponse;
import com.shop.productservice.service.ProductVariantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products/{productId}/variants")
@RequiredArgsConstructor
public class ProductVariantController {

    private final ProductVariantService productVariantService;

    @GetMapping
    public ApiResponse<List<ProductVariantResponse>> findByProductId(@PathVariable UUID productId) {
        return ApiResponse.ok(productVariantService.findByProductId(productId));
    }

    @GetMapping("/{variantId}")
    public ApiResponse<ProductVariantResponse> findById(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return ApiResponse.ok(productVariantService.findById(productId, variantId));
    }
}
