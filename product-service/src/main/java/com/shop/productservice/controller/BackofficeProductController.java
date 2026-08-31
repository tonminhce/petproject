package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Backoffice paged product list returning the FULL detail mapping (F2 STEP 0
 * — the reindex source endpoint for search-service, mirroring the
 * verify-purchase-added-to-order precedent). The storefront summary is not
 * sufficient for the reindex stream: it lacks brandName, categoryTitle,
 * description and updatedAt.
 *
 * <p>Security follows the {@code OrderController.verifyPurchase} SERVICE+ADMIN
 * pattern: search-service streams this list with its client-credentials
 * SERVICE token (an ADMIN-only rule would 403 the production reindex), while
 * human backoffice use is covered by ADMIN.</p>
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
            @RequestParam(defaultValue = "20") int size) {
        ProductFilter filter = new ProductFilter(categoryId, brandId, status);
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        return ApiResponse.ok(productService.findAllDetail(filter, pageable));
    }
}
