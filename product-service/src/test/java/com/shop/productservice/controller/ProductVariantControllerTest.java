package com.shop.productservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.response.ProductVariantResponse;
import com.shop.productservice.service.ProductVariantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductVariantController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ProductVariantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductVariantService productVariantService;

    private final UUID productId = UUID.randomUUID();
    private final UUID variantId = UUID.randomUUID();

    @Test
    void findByProductId_returnsList() throws Exception {
        ProductVariantResponse response = new ProductVariantResponse(
            variantId, productId, "SKU-1", "Variant 1", BigDecimal.valueOf(100), 5,
            "{\"color\":\"black\"}", null, Instant.now(), Instant.now()
        );

        when(productVariantService.findByProductId(productId)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/products/{productId}/variants", productId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].sku").value("SKU-1"))
            .andExpect(jsonPath("$.data[0].title").value("Variant 1"));
    }

    @Test
    void findById_returnsSingleVariant() throws Exception {
        ProductVariantResponse response = new ProductVariantResponse(
            variantId, productId, "SKU-1", "Variant 1", BigDecimal.valueOf(100), 5,
            "{\"color\":\"black\"}", null, Instant.now(), Instant.now()
        );

        when(productVariantService.findById(productId, variantId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/products/{productId}/variants/{variantId}", productId, variantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sku").value("SKU-1"))
            .andExpect(jsonPath("$.data.title").value("Variant 1"));
    }
}
