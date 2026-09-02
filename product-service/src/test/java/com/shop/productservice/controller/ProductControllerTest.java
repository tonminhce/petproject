package com.shop.productservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.spring.autoconfigure.I18nAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
// I18nAutoConfiguration = the fleet MessageSource (messages/messages EN+VI) —
// the WebMvcTest slice otherwise ships Boot's empty basename-"messages" bean,
// so the H-2 constraint's {product.media.clear.conflict} could not interpolate.
@Import({ApiExceptionHandler.class, I18nAutoConfiguration.class})
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
            null, null, null, null, null, null, null, null, null);

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

    @Test
    void create_unknownMediaId_mapsTo404WithMed12004AndCommonI18nMessage() throws Exception {
        // Media epic spec D5/D6: the write-time gate rejects with MED-12004 —
        // the code is defined in common-core and the message resolves from the
        // COMMON i18n bundle (media.not_found) — no product-local key needed.
        when(productService.create(any()))
            .thenThrow(BusinessException.of(ErrorCode.MEDIA_NOT_FOUND));

        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", null,
            "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE,
            null, UUID.fromString("88888888-8888-8888-8888-888888888888"),
            null, null, null, null);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("MED-12004"))
            .andExpect(jsonPath("$.message").value("Media not found"));
    }

    // --- H-2: explicit clearMediaId on PUT (binding + validation) ---

    @Test
    void update_clearMediaIdTrue_bindsExplicitClearToService() throws Exception {
        when(productService.update(eq(ID), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/products/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clearMediaId\": true}"))
            .andExpect(status().isOk());

        ProductUpdateRequest sent = capturedUpdateRequest();
        assertThat(sent.clearMediaId()).as("explicit clear flag must reach the service").isTrue();
        assertThat(sent.mediaId()).isNull();
    }

    @Test
    void update_absentClearFlag_bindsNull_referenceUntouched() throws Exception {
        when(productService.update(eq(ID), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/products/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Renamed\"}"))
            .andExpect(status().isOk());

        ProductUpdateRequest sent = capturedUpdateRequest();
        assertThat(sent.clearMediaId()).as("omitting the flag must mean no clear").isNull();
    }

    @Test
    void update_clearMediaIdFalse_explicitKeep_reachesService() throws Exception {
        when(productService.update(eq(ID), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/products/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Renamed\", \"clearMediaId\": false}"))
            .andExpect(status().isOk());

        assertThat(capturedUpdateRequest().clearMediaId()).isFalse();
    }

    @Test
    void update_clearFlagTogetherWithMediaId_conflictingInput_rejected400() throws Exception {
        // H-2: clear=true + replacement mediaId is ambiguous — binding-time
        // validation rejects it on the common 400 channel (ERR-0422-V) with
        // the interpolated i18n message (both bundle texts name the flag; the
        // rendered locale depends on the request — see ProductI18nKeysTest
        // pinning the EN+VI keys).
        mockMvc.perform(put("/api/v1/products/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mediaId\": \"88888888-8888-8888-8888-888888888888\", \"clearMediaId\": true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"))
            .andExpect(jsonPath("$.errors[0]").value(
                org.hamcrest.Matchers.containsString("mediaClearConsistent: clearMediaId=true")));

        verifyNoInteractions(productService);
    }

    private ProductUpdateRequest capturedUpdateRequest() throws Exception {
        ArgumentCaptor<ProductUpdateRequest> captor = ArgumentCaptor.forClass(ProductUpdateRequest.class);
        verify(productService).update(eq(ID), captor.capture());
        return captor.getValue();
    }
}
