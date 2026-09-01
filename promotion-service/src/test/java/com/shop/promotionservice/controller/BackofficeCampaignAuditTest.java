package com.shop.promotionservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.dto.response.CampaignResponse;
import com.shop.promotionservice.service.CampaignService;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6): one mutating backoffice campaign endpoint
 * exercised through the {@code AuditAspect}. Collection POST has no path
 * variable, so the audit line carries {@code resourceId: null}.
 */
@WebMvcTest(value = BackofficeCampaignController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class BackofficeCampaignAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean CampaignService campaignService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    private final UUID campaignId = UUID.fromString("00000000-0000-0000-0000-000000008001");

    @Test
    void create_emitsAuditLineWithAnnotatedAction() throws Exception {
        when(campaignService.create(any(com.shop.promotionservice.dto.request.CampaignRequest.class)))
            .thenReturn(new CampaignResponse(campaignId, "SAVE10", "Save 10", "PERCENT",
                new BigDecimal("10"), null, null, null, null, null, null,
                CampaignStatus.INACTIVE, Instant.parse("2026-08-31T10:00:00Z"),
                Instant.parse("2026-08-31T10:00:00Z")));

        mockMvc.perform(post("/api/v1/backoffice/promotions")
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000008003"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"SAVE10","name":"Save 10","discountType":"PERCENT","discountValue":10}"""))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("campaign.create");
        assertThat(event.resourceType()).isEqualTo("campaign");
        assertThat(event.resourceId()).isNull();
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"campaign.create\"",
            "\"resourceType\":\"campaign\"");
    }
}
