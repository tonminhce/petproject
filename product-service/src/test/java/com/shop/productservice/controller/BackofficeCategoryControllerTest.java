package com.shop.productservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C13 fix — category CRUD moved off the storefront path; the write surface is
 * exercised here against the ADMIN-gated backoffice controller (audit
 * coverage follows the authorization level, rating-service precedent).
 */
@WebMvcTest(value = BackofficeCategoryController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeCategoryControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean CategoryService categoryService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private CategoryResponse response() {
        return new CategoryResponse(ID, "Phones", "phones", null, null);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000000003"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000000004"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    // --- ADMIN happy paths ---

    @Test
    void create_admin_returns200() throws Exception {
        when(categoryService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/v1/backoffice/categories")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Phones\",\"slug\":\"phones\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.title").value("Phones"));
    }

    @Test
    void update_admin_returns200() throws Exception {
        when(categoryService.update(eq(ID), any())).thenReturn(response());

        mockMvc.perform(put("/api/v1/backoffice/categories/{id}", ID)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Smartphones\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(ID.toString()));

        verify(categoryService).update(eq(ID), any());
    }

    @Test
    void delete_admin_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/categories/{id}", ID).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(categoryService).delete(ID);
    }

    // --- validation ---

    @Test
    void create_withInvalidDto_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/categories")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"slug\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(categoryService);
    }

    // --- business error-code mapping ---

    @Test
    void update_cycleDetected_returns409WithPrd20009() throws Exception {
        // C11: re-parenting a category under its own descendant is a 409 —
        // the mapped i18n key lives in the common bundles (EN+VI).
        when(categoryService.update(eq(ID), any()))
            .thenThrow(BusinessException.of(ErrorCode.CATEGORY_CYCLE_DETECTED));

        mockMvc.perform(put("/api/v1/backoffice/categories/{id}", ID)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PRD-2009"));
    }

    // --- security matrix ---

    @Test
    void create_customerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/categories")
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Phones\",\"slug\":\"phones\"}"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(categoryService);
    }

    @Test
    void create_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Phones\",\"slug\":\"phones\"}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(categoryService);
    }
}
