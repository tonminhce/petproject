package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Product;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProductMapper {

    private final ModelMapper modelMapper;

    public ProductMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public ProductSummaryResponse toSummaryResponse(Product product) {
        return new ProductSummaryResponse(
            product.getId(),
            product.getTitle(),
            product.getSlug(),
            product.getSku(),
            product.getPriceUnit(),
            product.getQuantity(),
            product.getStatus(),
            product.getImageUrl()
        );
    }

    public ProductDetailResponse toDetailResponse(Product product) {
        UUID categoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        String categoryTitle = product.getCategory() != null ? product.getCategory().getTitle() : null;
        UUID brandId = product.getBrand() != null ? product.getBrand().getId() : null;
        String brandName = product.getBrand() != null ? product.getBrand().getName() : null;
        return new ProductDetailResponse(
            product.getId(),
            product.getTitle(),
            product.getSlug(),
            product.getDescription(),
            product.getSku(),
            product.getPriceUnit(),
            product.getQuantity(),
            product.getStatus(),
            product.getImageUrl(),
            product.getWeight(),
            product.getDimensions(),
            categoryId,
            categoryTitle,
            brandId,
            brandName,
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }

    public Product toEntity(ProductCreateRequest request) {
        // RecordValueReader (registered in common-spring) lets STRICT + field-
        // matching mode see Java record components as source properties, so this
        // map is no longer a silent no-op (it was before the platform fix).
        Product p = modelMapper.map(request, Product.class);
        p.setId(null);   // force identity insert even if request carried an id
        return p;
    }

    public void partialUpdate(Product target, ProductUpdateRequest request) {
        if (request.title()       != null) target.setTitle(request.title());
        if (request.slug()        != null) target.setSlug(request.slug());
        if (request.description() != null) target.setDescription(request.description());
        if (request.sku()         != null) target.setSku(request.sku());
        if (request.priceUnit()   != null) target.setPriceUnit(request.priceUnit());
        if (request.quantity()    != null) target.setQuantity(request.quantity());
        if (request.status()      != null) target.setStatus(request.status());
        if (request.imageUrl()    != null) target.setImageUrl(request.imageUrl());
        if (request.weight()      != null) target.setWeight(request.weight());
        if (request.dimensions()  != null) target.setDimensions(request.dimensions());
    }
}