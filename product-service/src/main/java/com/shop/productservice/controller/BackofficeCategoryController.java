package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.service.CategoryService;
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
 * C13 fix — category CRUD moved out of the storefront {@link CategoryController}
 * into this ADMIN-only backoffice entry point.
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_CATEGORIES)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackofficeCategoryController {

    private final CategoryService categoryService;

    @PostMapping
    @Audited(action = "category.create", resourceType = "category")
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResponse.ok(categoryService.create(request), "Category created successfully");
    }

    @PutMapping("/{id}")
    @Audited(action = "category.update", resourceType = "category")
    public ApiResponse<CategoryResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.ok(categoryService.update(id, request), "Category updated successfully");
    }

    @DeleteMapping("/{id}")
    @Audited(action = "category.delete", resourceType = "category")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ApiResponse.message("Category deleted successfully");
    }
}
