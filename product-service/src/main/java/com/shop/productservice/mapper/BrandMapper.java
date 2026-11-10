package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class BrandMapper {

    private final ModelMapper modelMapper;

    public BrandMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public BrandResponse toResponse(Brand brand) {
        return new BrandResponse(
            brand.getId(),
            brand.getName(),
            brand.getSlug(),
            brand.getLogoUrl(),
            brand.getDescription()
        );
    }

    public Brand toEntity(BrandCreateRequest request) {
        Brand b = modelMapper.map(request, Brand.class);
        b.setId(null);
        return b;
    }

    public void partialUpdate(Brand target, BrandUpdateRequest request) {
        if (request.name()        != null) target.setName(request.name());
        if (request.slug()        != null) target.setSlug(request.slug());
        if (request.logoUrl()     != null) target.setLogoUrl(request.logoUrl());
        if (request.description() != null) target.setDescription(request.description());
    }
}