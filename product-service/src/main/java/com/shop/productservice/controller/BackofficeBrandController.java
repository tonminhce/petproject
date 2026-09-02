package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.service.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * C13 fix — brand CRUD moved out of the storefront {@link BrandController}
 * into this ADMIN-only backoffice entry point.
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_BRANDS)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackofficeBrandController {

    private final BrandService brandService;

    @PostMapping
    @Audited(action = "brand.create", resourceType = "brand")
    public ApiResponse<BrandResponse> create(@Valid @RequestBody BrandCreateRequest request) {
        return ApiResponse.ok(brandService.create(request), "Brand created successfully");
    }

    @PutMapping("/{id}")
    @Audited(action = "brand.update", resourceType = "brand")
    public ApiResponse<BrandResponse> update(@PathVariable UUID id,
                                              @Valid @RequestBody BrandUpdateRequest request) {
        return ApiResponse.ok(brandService.update(id, request), "Brand updated successfully");
    }

    @DeleteMapping("/{id}")
    @Audited(action = "brand.delete", resourceType = "brand")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        brandService.delete(id);
        return ApiResponse.message("Brand deleted successfully");
    }
}
