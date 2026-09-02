package com.shop.productservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
// C13 fix — the storefront controller keeps only the public read surface;
// the write tests moved to BackofficeProductControllerTest (the write
// endpoints now live behind the ADMIN-gated backoffice path).
@Import(ApiExceptionHandler.class)
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductService productService;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ProductDetailResponse sample() {
        return new ProductDetailResponse(ID, "iPhone 15", "iphone-15", null, "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    @Test
    void findAll_capsPageSizeAndDefaultsStatusToActive() throws Exception {
        when(productService.findAll(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == com.shop.common.core.constants.PageableConstant.MAX_PAGE_SIZE)))
            .thenReturn(com.shop.common.core.viewmodel.PageResponse.of(java.util.List.of(), 0, com.shop.common.core.constants.PageableConstant.MAX_PAGE_SIZE, 0));

        mockMvc.perform(get("/api/v1/products").param("size", "9999"))
            .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<com.shop.productservice.dto.ProductFilter> filter = org.mockito.ArgumentCaptor.forClass(com.shop.productservice.dto.ProductFilter.class);
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(productService).findAll(filter.capture(), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(filter.getValue().status()).isEqualTo(ProductStatus.ACTIVE);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(com.shop.common.core.constants.PageableConstant.MAX_PAGE_SIZE);
    }

    @Test
    void findById_returns200WithApiResponse() throws Exception {
        when(productService.findById(ID)).thenReturn(sample());

        mockMvc.perform(get("/api/v1/products/{id}", ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void findBySlug_returns200() throws Exception {
        when(productService.findBySlug("iphone-15")).thenReturn(sample());

        mockMvc.perform(get("/api/v1/products/slug/{slug}", "iphone-15"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.slug").value("iphone-15"));
    }
}
