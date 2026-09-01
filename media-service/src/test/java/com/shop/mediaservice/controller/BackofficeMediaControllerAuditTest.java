package com.shop.mediaservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaUploadService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec §4.3): the ADMIN-gated media upload/delete
 * exercised through the real {@code AuditAspect} — one structured JSON
 * audit line per mutation, mirroring the product-side audit contract.
 */
@WebMvcTest(value = BackofficeMediaController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class BackofficeMediaControllerAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean MediaUploadService uploadService;
    @MockitoBean MediaLifecycleService lifecycleService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    private static final UUID MEDIA_ID = UUID.fromString("b1000000-0000-0000-0000-000000000001");

    @Test
    void upload_emitsAuditLineWithAnnotatedAction() throws Exception {
        when(uploadService.upload(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new MediaResponse(MEDIA_ID, "a".repeat(64), "image/jpeg", 4,
                "/api/v1/medias/" + MEDIA_ID, List.of(), false));

        mockMvc.perform(multipart("/api/v1/backoffice/medias")
                    .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3, 4}))
                    .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
                        .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isCreated());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("media.upload");
        assertThat(event.resourceType()).isEqualTo("media");
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"media.upload\"",
            "\"resourceType\":\"media\"");
    }

    @Test
    void delete_emitsAuditLineWithAnnotatedActionAndResourceId() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/medias/{id}", MEDIA_ID)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("media.delete");
        assertThat(event.resourceType()).isEqualTo("media");
        assertThat(event.resourceId()).isEqualTo(MEDIA_ID.toString());
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"media.delete\"",
            "\"resourceType\":\"media\"",
            "\"resourceId\":\"" + MEDIA_ID + "\"");
    }
}
