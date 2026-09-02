package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
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

@RestController
@RequestMapping(ApiPaths.PRODUCTS)
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<PageResponse<ProductSummaryResponse>> findAll(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ProductFilter filter = new ProductFilter(categoryId, brandId, status);
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(productService.findAll(filter, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> findById(@PathVariable UUID id) {
        return ApiResponse.ok(productService.findById(id));
    }

    @GetMapping("/slug/{slug}")
    public ApiResponse<ProductDetailResponse> findBySlug(@PathVariable String slug) {
        return ApiResponse.ok(productService.findBySlug(slug));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "product.create", resourceType = "product")
    public ApiResponse<ProductDetailResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ApiResponse.ok(productService.create(request), "Product created successfully");
    }

    /**
     * Partial update (ruling H-2 media-reference semantics): fields present in
     * the body overwrite their current value; absent/null fields keep it.
     * {@code mediaId} present → HEAD-verified against media-service (Option C
     * write-time gate, MED-12004 on unknown). Clearing the reference is
     * EXPLICIT: send {@code clearMediaId=true} with no {@code mediaId} — the
     * stored reference is removed and the derived image falls back to the
     * legacy free-text {@code imageUrl}. {@code clearMediaId=true} together
     * with a non-null {@code mediaId} is rejected at binding time (400,
     * ERR-0422-V, i18n {@code product.media.clear.conflict}); omitting the
     * flag never touches the reference.
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
