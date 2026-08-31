package com.shop.productservice.mapper;

import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.Product;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperRatingFieldsTest {

    private ProductMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ProductMapper(new ModelMapper());
    }

    private Product product() {
        Product p = Product.builder()
            .title("iPhone 15")
            .slug("iphone-15")
            .sku("IP15-001")
            .priceUnit(new BigDecimal("999.00"))
            .quantity(10)
            .status(ProductStatus.ACTIVE)
            .build();
        p.setAvgRating(new BigDecimal("4.32"));
        p.setRatingCount(27);
        return p;
    }

    @Test
    void detailResponse_exposesRatingFields() {
        ProductDetailResponse resp = mapper.toDetailResponse(product());

        assertThat(resp.avgRating()).isEqualByComparingTo("4.32");
        assertThat(resp.ratingCount()).isEqualTo(27);
    }

    @Test
    void summaryResponse_exposesRatingFields() {
        ProductSummaryResponse resp = mapper.toSummaryResponse(product());

        assertThat(resp.avgRating()).isEqualByComparingTo("4.32");
        assertThat(resp.ratingCount()).isEqualTo(27);
    }
}
