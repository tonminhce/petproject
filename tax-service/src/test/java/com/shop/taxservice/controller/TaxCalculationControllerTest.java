package com.shop.taxservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.taxservice.dto.request.TaxCalculateRequest;
import com.shop.taxservice.dto.response.TaxCalculateResponse;
import com.shop.taxservice.service.TaxCalculationService;
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
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = TaxCalculationController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class TaxCalculationControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean TaxCalculationService taxCalculationService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID taxClassId = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    private TaxCalculateRequest request() {
        return new TaxCalculateRequest(taxClassId, "DE", "10115", new BigDecimal("100.00"));
    }

    private String calculateBody() {
        return """
            {"taxClassId": "%s", "country": "DE", "postalCode": "10115", "amount": 100.00}
            """.formatted(taxClassId);
    }

    @Test
    void calculate_serviceRole_returns200WithPinnedFields() throws Exception {
        when(taxCalculationService.calculate(request()))
            .thenReturn(new TaxCalculateResponse(new BigDecimal("19.00"), new BigDecimal("19.00")));

        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(jwt().jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(calculateBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taxAmount").value(19.00))
            .andExpect(jsonPath("$.data.appliedRate").value(19.00));
    }

    @Test
    void calculate_adminRole_returns200() throws Exception {
        when(taxCalculationService.calculate(request()))
            .thenReturn(new TaxCalculateResponse(new BigDecimal("19.00"), new BigDecimal("19.00")));

        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(jwt().jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(calculateBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taxAmount").value(19.00));
    }

    @Test
    void calculate_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(calculateBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void calculate_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(jwt().jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(calculateBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void calculate_lowercaseCountry_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(jwt().jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"taxClassId": "%s", "country": "de", "postalCode": "10115", "amount": 100.00}
                    """.formatted(taxClassId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void calculate_negativeAmount_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(jwt().jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"taxClassId": "%s", "country": "DE", "postalCode": "10115", "amount": -5.00}
                    """.formatted(taxClassId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void calculate_noMatchingRate_returns404WithTax8002() throws Exception {
        when(taxCalculationService.calculate(request()))
            .thenThrow(BusinessException.of(ErrorCode.NO_MATCHING_RATE, "DE"));

        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(calculateBody()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("TAX-8002"));
    }
}
