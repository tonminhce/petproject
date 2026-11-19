package com.shop.productservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.service.BrandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BrandController.class)
@AutoConfigureMockMvc(addFilters = false)
// Mirrors ProductControllerTest: without the handler, BusinessException/
// validation failures would surface as raw 500s instead of the ApiResponse
// error envelope.
@Import(ApiExceptionHandler.class)
class BrandControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean BrandService brandService;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void findById_returns200WithApiResponse() throws Exception {
        BrandResponse resp = new BrandResponse(ID, "Acme", "acme", null, null);
        when(brandService.findById(ID)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/brands/{id}", ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("00000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.data.name").value("Acme"));
    }
}
