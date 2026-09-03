package com.shop.productservice.controller;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.productservice.dto.request.ProductVariantCreateRequest;
import com.shop.productservice.dto.request.ProductVariantUpdateRequest;
import com.shop.productservice.dto.response.ProductVariantResponse;
import com.shop.productservice.service.ProductVariantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/backoffice/products/{productId}/variants")
@RequiredArgsConstructor
public class BackofficeProductVariantController {

    private final ProductVariantService productVariantService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<List<ProductVariantResponse>> findByProductId(@PathVariable UUID productId) {
        return ApiResponse.ok(productVariantService.findByProductId(productId));
    }

    @GetMapping("/{variantId}")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<ProductVariantResponse> findById(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return ApiResponse.ok(productVariantService.findById(productId, variantId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "product_variant.create", resourceType = "product_variant")
    public ApiResponse<ProductVariantResponse> create(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductVariantCreateRequest request) {
        return ApiResponse.ok(productVariantService.create(productId, request), "Product variant created successfully");
    }

    @PutMapping("/{variantId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "product_variant.update", resourceType = "product_variant")
    public ApiResponse<ProductVariantResponse> update(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody ProductVariantUpdateRequest request) {
        return ApiResponse.ok(productVariantService.update(productId, variantId, request));
    }

    @DeleteMapping("/{variantId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Audited(action = "product_variant.delete", resourceType = "product_variant")
    public void delete(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        productVariantService.delete(productId, variantId);
    }
}
