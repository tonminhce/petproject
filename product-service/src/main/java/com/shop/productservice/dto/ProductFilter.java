package com.shop.productservice.dto;

import com.shop.productservice.entity.ProductStatus;

import java.util.UUID;

public record ProductFilter(UUID categoryId, UUID brandId, ProductStatus status) {}