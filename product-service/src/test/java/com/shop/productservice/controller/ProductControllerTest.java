package com.shop.productservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductService productService;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ProductDetailResponse sample() {
        return new ProductDetailResponse(ID, "iPhone 15", "iphone-15", null, "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null, null, null,
            null, null);
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

    @Test
    void create_withInvalidDto_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest("", "", null, "",
            null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void update_rejectsOversizedField() throws Exception {
        String str = "x".repeat(2001);
        String body = String.format("""
            {"description": "%s"}
            """, str);

        mockMvc.perform(put("/api/v1/products/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(productService);
    }
}
