package com.shop.productservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.autoconfigure.I18nAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security matrix for GET /api/v1/backoffice/products — the SERVICE+ADMIN
 * paged full-snapshot list that search-service reindex streams from (F2 STEP
 * 0, verify-purchase-added-to-order precedent). The response must carry the
 * FULL detail mapping — the storefront summary is NOT acceptable for the
 * reindex source (missing brandName/categoryTitle/updatedAt/description).
 *
 * <p>C13 fix — the ADMIN-gated human CRUD (POST/PUT/DELETE) moved here from
 * the storefront ProductController; its binding/validation/audit-expectation
 * tests migrated with it and target the backoffice path.</p>
 */
@WebMvcTest(value = BackofficeProductController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
// I18nAutoConfiguration = the fleet MessageSource (messages/messages EN+VI) —
// the WebMvcTest slice otherwise ships Boot's empty basename-"messages" bean,
// so the H-2 constraint's {product.media.clear.conflict} could not interpolate.
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class, I18nAutoConfiguration.class})
class BackofficeProductControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductService productService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Every snapshot field the reindex (spec D2/D3) needs, populated. */
    private ProductDetailResponse sample() {
        return new ProductDetailResponse(
            ID, "iPhone 15", "iphone-15", "Apple smartphone", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, "http://img/iphone.png",
            null,
            new BigDecimal("0.3"), "15x8x8cm", new BigDecimal("4.50"), 12,
            UUID.fromString("c0000000-0000-0000-0000-00000000000a"), "Phones",
            UUID.fromString("b0000000-0000-0000-0000-00000000000a"), "Apple",
            Instant.parse("2026-08-30T10:00:00Z"), Instant.parse("2026-08-31T09:30:00Z"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor serviceClient() {
        return jwt().jwt(j -> j.subject("search-service"))
            .authorities(createAuthorityList("ROLE_SERVICE"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007001"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    // --- authorized ---

    @Test
    void findAll_serviceRole_returns200() throws Exception {
        when(productService.findAllDetail(any(ProductFilter.class), any(Pageable.class)))
            .thenReturn(com.shop.common.core.viewmodel.PageResponse.of(List.of(), 0, 200, 0));

        mockMvc.perform(get("/api/v1/backoffice/products").with(serviceClient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void findAll_admin_returns200WithFullSnapshotFields() throws Exception {
        when(productService.findAllDetail(any(ProductFilter.class), any(Pageable.class)))
            .thenReturn(com.shop.common.core.viewmodel.PageResponse.of(List.of(sample()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/backoffice/products").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            // FULL field coverage — every D2 snapshot field must survive the wire
            .andExpect(jsonPath("$.data.content[0].id").value(ID.toString()))
            .andExpect(jsonPath("$.data.content[0].title").value("iPhone 15"))
            .andExpect(jsonPath("$.data.content[0].slug").value("iphone-15"))
            .andExpect(jsonPath("$.data.content[0].description").value("Apple smartphone"))
            .andExpect(jsonPath("$.data.content[0].priceUnit").value(999.00))
            .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.content[0].imageUrl").value("http://img/iphone.png"))
            .andExpect(jsonPath("$.data.content[0].avgRating").value(4.50))
            .andExpect(jsonPath("$.data.content[0].ratingCount").value(12))
            .andExpect(jsonPath("$.data.content[0].categoryId").value("c0000000-0000-0000-0000-00000000000a"))
            .andExpect(jsonPath("$.data.content[0].categoryTitle").value("Phones"))
            .andExpect(jsonPath("$.data.content[0].brandId").value("b0000000-0000-0000-0000-00000000000a"))
            .andExpect(jsonPath("$.data.content[0].brandName").value("Apple"))
            .andExpect(jsonPath("$.data.content[0].updatedAt").value("2026-08-31T09:30:00Z"))
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void findAll_passesFilterAndCappedPageable() throws Exception {
        when(productService.findAllDetail(any(ProductFilter.class), any(Pageable.class)))
            .thenReturn(com.shop.common.core.viewmodel.PageResponse.of(List.of(), 0, 200, 0));

        mockMvc.perform(get("/api/v1/backoffice/products")
                .param("status", "ACTIVE")
                .param("page", "3")
                .param("size", "5000")
                .with(admin()))
            .andExpect(status().isOk());

        verify(productService).findAllDetail(
            refEq(new ProductFilter(null, null, ProductStatus.ACTIVE)),
            refEq(PageRequest.of(3, com.shop.common.core.constants.PageableConstant.MAX_PAGE_SIZE)));
    }

    // --- C13: ADMIN write surface migrated from the storefront controller ---

    @Test
    void create_withInvalidDto_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest("", "", null, "",
            null, null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/backoffice/products")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(productService);
    }

    @Test
    void update_rejectsOversizedField() throws Exception {
        String str = "x".repeat(2001);
        String body = String.format("""
            {"description": "%s"}
            """, str);

        mockMvc.perform(put("/api/v1/backoffice/products/{id}", ID)
                .with(admin())
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

        mockMvc.perform(post("/api/v1/backoffice/products")
                .with(admin())
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

        mockMvc.perform(put("/api/v1/backoffice/products/{id}", ID)
                .with(admin())
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

        mockMvc.perform(put("/api/v1/backoffice/products/{id}", ID)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Renamed\"}"))
            .andExpect(status().isOk());

        ProductUpdateRequest sent = capturedUpdateRequest();
        assertThat(sent.clearMediaId()).as("omitting the flag must mean no clear").isNull();
    }

    @Test
    void update_clearMediaIdFalse_explicitKeep_reachesService() throws Exception {
        when(productService.update(eq(ID), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/backoffice/products/{id}", ID)
                .with(admin())
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
        mockMvc.perform(put("/api/v1/backoffice/products/{id}", ID)
                .with(admin())
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

    // --- security matrix ---

    @Test
    void findAll_customerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/products").with(customer()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(productService);
    }

    @Test
    void findAll_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/products"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(productService);
    }
}
