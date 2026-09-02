package com.shop.productservice.controller;

import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.service.BrandService;
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
 * C13 fix — brand CRUD moved off the storefront path; the write surface is
 * exercised here against the ADMIN-gated backoffice controller (audit
 * coverage follows the authorization level, rating-service precedent).
 */
@WebMvcTest(value = BackofficeBrandController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeBrandControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean BrandService brandService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private BrandResponse response() {
        return new BrandResponse(ID, "Acme", "acme", null, null);
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
        when(brandService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/v1/backoffice/brands")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Acme"));
    }

    @Test
    void update_admin_returns200() throws Exception {
        when(brandService.update(eq(ID), any())).thenReturn(response());

        mockMvc.perform(put("/api/v1/backoffice/brands/{id}", ID)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Renamed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(ID.toString()));

        verify(brandService).update(eq(ID), any());
    }

    @Test
    void delete_admin_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/brands/{id}", ID).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(brandService).delete(ID);
    }

    // --- validation ---

    @Test
    void create_withInvalidDto_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/brands")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"slug\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(brandService);
    }

    // --- security matrix ---

    @Test
    void create_customerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/brands")
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(brandService);
    }

    @Test
    void create_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/brands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(brandService);
    }
}
