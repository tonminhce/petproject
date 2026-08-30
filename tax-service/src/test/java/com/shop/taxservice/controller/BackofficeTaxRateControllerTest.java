package com.shop.taxservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.taxservice.dto.response.TaxRateResponse;
import com.shop.taxservice.service.TaxRateService;
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

@WebMvcTest(value = BackofficeTaxRateController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeTaxRateControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean TaxRateService taxRateService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID rateId = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private final UUID classId = UUID.fromString("00000000-0000-0000-0000-00000000c002");

    private String rateBody() {
        return """
            {"taxClassId": "%s", "country": "DE", "postalCode": "10115", "ratePct": 19.00}
            """.formatted(classId);
    }

    @Test
    void list_admin_returns200WithRatesFilteredByClass() throws Exception {
        when(taxRateService.list(classId)).thenReturn(List.of(
            new TaxRateResponse(rateId, classId, "DE", "10115", new BigDecimal("19.00"))));

        mockMvc.perform(get("/api/v1/backoffice/tax-rates").param("classId", classId.toString())
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].id").value(rateId.toString()))
            .andExpect(jsonPath("$.data[0].taxClassId").value(classId.toString()))
            .andExpect(jsonPath("$.data[0].country").value("DE"))
            .andExpect(jsonPath("$.data[0].ratePct").value(19.00));
    }

    @Test
    void get_admin_returns200WithRate() throws Exception {
        when(taxRateService.get(rateId))
            .thenReturn(new TaxRateResponse(rateId, classId, "DE", "10115", new BigDecimal("19.00")));

        mockMvc.perform(get("/api/v1/backoffice/tax-rates/{id}", rateId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(rateId.toString()))
            .andExpect(jsonPath("$.data.country").value("DE"))
            .andExpect(jsonPath("$.data.postalCode").value("10115"));
    }

    @Test
    void create_admin_returns200WithCreatedRate() throws Exception {
        when(taxRateService.create(org.mockito.ArgumentMatchers.any(
                com.shop.taxservice.dto.request.TaxRateRequest.class)))
            .thenReturn(new TaxRateResponse(rateId, classId, "DE", "10115", new BigDecimal("19.00")));

        mockMvc.perform(post("/api/v1/backoffice/tax-rates")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(rateId.toString()))
            .andExpect(jsonPath("$.data.ratePct").value(19.00));
    }

    @Test
    void update_admin_returns200WithUpdatedRate() throws Exception {
        when(taxRateService.update(org.mockito.ArgumentMatchers.eq(rateId),
                org.mockito.ArgumentMatchers.any(com.shop.taxservice.dto.request.TaxRateRequest.class)))
            .thenReturn(new TaxRateResponse(rateId, classId, "DE", "10115", new BigDecimal("7.00")));

        mockMvc.perform(put("/api/v1/backoffice/tax-rates/{id}", rateId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ratePct").value(7.00));
    }

    @Test
    void delete_admin_returns200WithMessage() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/tax-rates/{id}", rateId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tax rate deleted successfully"));

        verify(taxRateService).delete(rateId);
    }

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/tax-rates"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_serviceRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/tax-rates")
                .with(jwt().jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void delete_userRole_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/tax-rates/{id}", rateId)
                .with(jwt().jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void create_lowercaseCountry_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-rates")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"taxClassId": "%s", "country": "de", "postalCode": "10115", "ratePct": 19.00}
                    """.formatted(classId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void create_negativeRatePct_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-rates")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"taxClassId": "%s", "country": "DE", "postalCode": "10115", "ratePct": -1.00}
                    """.formatted(classId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void get_unknownRate_returns404WithTax8005() throws Exception {
        when(taxRateService.get(rateId))
            .thenThrow(BusinessException.of(ErrorCode.TAX_RATE_NOT_FOUND, rateId));

        mockMvc.perform(get("/api/v1/backoffice/tax-rates/{id}", rateId)
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("TAX-8005"));
    }

    @Test
    void create_duplicateRate_returns409WithTax8003() throws Exception {
        when(taxRateService.create(org.mockito.ArgumentMatchers.any(
                com.shop.taxservice.dto.request.TaxRateRequest.class)))
            .thenThrow(BusinessException.of(ErrorCode.DUPLICATE_TAX_RATE, "DE"));

        mockMvc.perform(post("/api/v1/backoffice/tax-rates")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rateBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("TAX-8003"));
    }
}
