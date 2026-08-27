package com.shop.productservice.service;

import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;

import java.util.List;

public interface CategoryService {

    List<CategoryResponse> findAll();

    List<CategoryTreeResponse> findTree();

    CategoryResponse findById(Long id);

    CategoryResponse create(CategoryCreateRequest request);

    CategoryResponse update(Long id, CategoryUpdateRequest request);

    void delete(Long id);
}