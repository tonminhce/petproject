package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Product;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

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
        Long categoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        String categoryTitle = product.getCategory() != null ? product.getCategory().getTitle() : null;
        Long brandId = product.getBrand() != null ? product.getBrand().getId() : null;
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
        // Manual copy — ModelMapper 3.2.6 STRICT + field-matching mode does not
        // see Java record components as source properties, so the auto-map leaves
        // every field null. A platform-level ModelMapper ValueReader fix is tracked
        // as a follow-up; until then, every mapper that takes a record source
        // must do this manual copy.
        Product p = new Product();
        p.setId(null);
        p.setTitle(request.title());
        p.setSlug(request.slug());
        p.setDescription(request.description());
        p.setSku(request.sku());
        p.setPriceUnit(request.priceUnit());
        p.setQuantity(request.quantity());
        p.setStatus(request.status());
        p.setImageUrl(request.imageUrl());
        p.setWeight(request.weight());
        p.setDimensions(request.dimensions());
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