package com.shop.taxservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.taxservice.dto.response.TaxCalculateResponse;
import com.shop.taxservice.dto.response.TaxClassResponse;
import com.shop.taxservice.dto.response.TaxRateResponse;
import com.shop.taxservice.service.TaxCalculationService;
import com.shop.taxservice.service.TaxClassService;
import com.shop.taxservice.service.TaxRateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6 + R3 veto): one mutating endpoint per backoffice
 * tax controller plus the SERVICE-gated calculation endpoint (all live in
 * this service) exercised through the {@code AuditAspect} — proving the
 * distinct action/resourceType pairs, the path-variable resourceId on the
 * idempotent {id} route, and the service-actor discrimination on calculate.
 */
@WebMvcTest(value = {BackofficeTaxClassController.class, BackofficeTaxRateController.class,
        TaxCalculationController.class},
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class BackofficeTaxAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean TaxClassService taxClassService;
    @MockitoBean TaxRateService taxRateService;
    @MockitoBean TaxCalculationService taxCalculationService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    private final UUID classId = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private final UUID rateId = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @Test
    void createTaxClass_emitsAuditLineWithTaxClassAction() throws Exception {
        when(taxClassService.create(any(com.shop.taxservice.dto.request.TaxClassRequest.class)))
            .thenReturn(new TaxClassResponse(classId, "Standard", new BigDecimal("19.00")));

        mockMvc.perform(post("/api/v1/backoffice/tax-classes")
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000b003"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Standard","defaultRatePct":19.00}"""))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("tax-class.create");
        assertThat(event.resourceType()).isEqualTo("tax-class");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains("\"action\":\"tax-class.create\"");
    }

    @Test
    void updateTaxRate_emitsAuditLineWithRateActionAndResourceId() throws Exception {
        when(taxRateService.update(eq(rateId),
                any(com.shop.taxservice.dto.request.TaxRateRequest.class)))
            .thenReturn(new TaxRateResponse(rateId, classId, "DE", null, new BigDecimal("19.00")));

        mockMvc.perform(put("/api/v1/backoffice/tax-rates/{id}", rateId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000b003"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"taxClassId":"%s","country":"DE","ratePct":19.00}""".formatted(classId)))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("tax-rate.update");
        assertThat(event.resourceType()).isEqualTo("tax-rate");
        assertThat(event.resourceId()).isEqualTo(rateId.toString());
        assertThat(event.toJson()).contains(
            "\"action\":\"tax-rate.update\"",
            "\"resourceId\":\"" + rateId + "\"");
    }

    @Test
    void calculate_byServiceToken_emitsAuditLineWithServiceActor() throws Exception {
        when(taxCalculationService.calculate(
                any(com.shop.taxservice.dto.request.TaxCalculateRequest.class)))
            .thenReturn(new TaxCalculateResponse(new BigDecimal("19.00"), new BigDecimal("19.00")));

        mockMvc.perform(post("/api/v1/backoffice/tax-rates/calculate")
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000b004")
                        .claim("azp", "checkout-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"taxClassId":"%s","country":"DE","amount":100.00}""".formatted(classId)))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("tax.calculate");
        assertThat(event.resourceType()).isEqualTo("tax-calculation");
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.actorId()).isEqualTo("checkout-service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains("\"action\":\"tax.calculate\"");
    }
}
