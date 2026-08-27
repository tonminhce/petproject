package com.shop.productservice.dto;

import com.shop.productservice.entity.ProductStatus;

public record ProductFilter(Long categoryId, Long brandId, ProductStatus status) {}