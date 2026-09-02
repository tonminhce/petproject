package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Backoffice entry point for product operations. Mixes two trust models
 * deliberately: the reindex list endpoint uses SERVICE+ADMIN (search-service
 * streams this with its client-credentials token; an ADMIN-only rule would 403
 * the production reindex), while human CRUD lives in ADMIN-only methods added
 * here under the C13 fix.
 *
 * <p>Security model mirrors {@code OrderController.verifyPurchase}: class-level
 * isAuthenticated() gives every authenticated identity access, then each method
 * downgrades to a tighter rule.</p>
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_PRODUCTS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class BackofficeProductController {

    private final ProductService productService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<PageResponse<ProductDetailResponse>> findAll(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.MAX_PAGE_SIZE) int size) {
        ProductFilter filter = new ProductFilter(categoryId, brandId, status);
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        return ApiResponse.ok(productService.findAllDetail(filter, pageable));
    }

    // C13 fix — product CRUD moved here from the storefront ProductController.

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "product.create", resourceType = "product")
    public ApiResponse<ProductDetailResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ApiResponse.ok(productService.create(request), "Product created successfully");
    }

    /**
     * Partial update (ruling H-2 media-reference semantics): fields present in
     * the body overwrite their current value; absent/null fields keep it. See
     * storefront ProductController (removed) for the full doc-comment — kept
     * here in case backoffice editing needs the contract.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "product.update", resourceType = "product")
    public ApiResponse<ProductDetailResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(productService.update(id, request), "Product updated successfully");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "product.delete", resourceType = "product")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ApiResponse.message("Product deleted successfully");
    }
}
