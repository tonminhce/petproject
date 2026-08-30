package com.shop.taxservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.taxservice.dto.response.TaxClassResponse;
import com.shop.taxservice.service.TaxClassService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BackofficeTaxClassController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeTaxClassControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean TaxClassService taxClassService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID classId = UUID.fromString("00000000-0000-0000-0000-00000000b001");

    private String classBody() {
        return """
            {"name": "Standard", "defaultRatePct": 19.00}
            """;
    }

    @Test
    void list_admin_returns200WithClasses() throws Exception {
        when(taxClassService.list()).thenReturn(List.of(
            new TaxClassResponse(classId, "Standard", new BigDecimal("19.00"))));

        mockMvc.perform(get("/api/v1/backoffice/tax-classes")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].id").value(classId.toString()))
            .andExpect(jsonPath("$.data[0].name").value("Standard"))
            .andExpect(jsonPath("$.data[0].defaultRatePct").value(19.00));
    }

    @Test
    void get_admin_returns200WithClass() throws Exception {
        when(taxClassService.get(classId))
            .thenReturn(new TaxClassResponse(classId, "Reduced", new BigDecimal("7.00")));

        mockMvc.perform(get("/api/v1/backoffice/tax-classes/{id}", classId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(classId.toString()))
            .andExpect(jsonPath("$.data.name").value("Reduced"))
            .andExpect(jsonPath("$.data.defaultRatePct").value(7.00));
    }

    @Test
    void create_admin_returns200WithCreatedClass() throws Exception {
        when(taxClassService.create(org.mockito.ArgumentMatchers.any(
                com.shop.taxservice.dto.request.TaxClassRequest.class)))
            .thenReturn(new TaxClassResponse(classId, "Standard", new BigDecimal("19.00")));

        mockMvc.perform(post("/api/v1/backoffice/tax-classes")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(classBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(classId.toString()))
            .andExpect(jsonPath("$.data.name").value("Standard"));
    }

    @Test
    void update_admin_returns200WithUpdatedClass() throws Exception {
        when(taxClassService.update(org.mockito.ArgumentMatchers.eq(classId),
                org.mockito.ArgumentMatchers.any(com.shop.taxservice.dto.request.TaxClassRequest.class)))
            .thenReturn(new TaxClassResponse(classId, "Renamed", new BigDecimal("20.00")));

        mockMvc.perform(put("/api/v1/backoffice/tax-classes/{id}", classId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(classBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Renamed"))
            .andExpect(jsonPath("$.data.defaultRatePct").value(20.00));
    }

    @Test
    void delete_admin_returns200WithMessage() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/tax-classes/{id}", classId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tax class deleted successfully"));

        verify(taxClassService).delete(classId);
    }

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/tax-classes"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_serviceRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/tax-classes")
                .with(jwt().jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void delete_serviceRole_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/tax-classes/{id}", classId)
                .with(jwt().jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void create_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-classes")
                .with(jwt().jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(classBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void create_blankName_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-classes")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "  ", "defaultRatePct": 19.00}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void get_unknownClass_returns404WithTax8001() throws Exception {
        when(taxClassService.get(classId))
            .thenThrow(BusinessException.of(ErrorCode.TAX_CLASS_NOT_FOUND, classId));

        mockMvc.perform(get("/api/v1/backoffice/tax-classes/{id}", classId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("TAX-8001"));
    }

    @Test
    void delete_classInUse_returns409WithTax8004() throws Exception {
        doThrow(BusinessException.of(ErrorCode.TAX_CLASS_IN_USE, classId))
            .when(taxClassService).delete(classId);

        mockMvc.perform(delete("/api/v1/backoffice/tax-classes/{id}", classId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("TAX-8004"));
    }
}
