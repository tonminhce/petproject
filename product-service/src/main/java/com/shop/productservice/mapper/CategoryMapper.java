package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CategoryMapper {

    private final ModelMapper modelMapper;

    public CategoryMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
            category.getId(),
            category.getTitle(),
            category.getSlug(),
            category.getImageUrl(),
            category.getParent() != null ? category.getParent().getId() : null
        );
    }

    public CategoryTreeResponse toTreeResponse(Category category, List<CategoryTreeResponse> children) {
        return new CategoryTreeResponse(
            category.getId(),
            category.getTitle(),
            category.getSlug(),
            category.getImageUrl(),
            category.getParent() != null ? category.getParent().getId() : null,
            children != null ? children : new ArrayList<>()
        );
    }

    public Category toEntity(CategoryCreateRequest request) {
        Category c = modelMapper.map(request, Category.class);
        c.setId(null);
        return c;
    }

    public void partialUpdate(Category target, CategoryUpdateRequest request) {
        if (request.title()     != null) target.setTitle(request.title());
        if (request.slug()      != null) target.setSlug(request.slug());
        if (request.imageUrl() != null) target.setImageUrl(request.imageUrl());
    }
}