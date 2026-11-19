package com.shop.ratingservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.service.RatingService;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6): one mutating backoffice endpoint exercised
 * end-to-end through the {@code AuditAspect} to prove the structured JSON
 * audit line is emitted with the annotated action/resourceType, the path
 * {@code id} as resourceId and the JWT {@code sub} as actor.
 */
@WebMvcTest(value = BackofficeRatingController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class BackofficeRatingAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean RatingService ratingService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    private final UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000005001");
    private final UUID ratingId = UUID.fromString("00000000-0000-0000-0000-000000005004");

    @Test
    void hide_emitsAuditLineWithAnnotatedActionAndResourceId() throws Exception {
        when(ratingService.hide(ratingId, adminId, "Abusive comment"))
            .thenReturn(new RatingResponse(ratingId,
                UUID.fromString("00000000-0000-0000-0000-000000005003"),
                UUID.fromString("00000000-0000-0000-0000-000000005002"), 5,
                "Great product, really enjoyed it", true, true,
                Instant.parse("2026-08-31T10:00:00Z"), Instant.parse("2026-08-31T09:00:00Z")));

        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(jwt().jwt(j -> j.subject(adminId.toString()))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Abusive comment"}"""))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("rating.hide");
        assertThat(event.resourceType()).isEqualTo("rating");
        assertThat(event.resourceId()).isEqualTo(ratingId.toString());
        assertThat(event.actorId()).isEqualTo(adminId.toString());
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"rating.hide\"",
            "\"resourceType\":\"rating\"",
            "\"resourceId\":\"" + ratingId + "\"",
            "\"actor\":{\"id\":\"" + adminId + "\",\"type\":\"user\"}",
            "\"outcome\":\"success\"");
    }
}
