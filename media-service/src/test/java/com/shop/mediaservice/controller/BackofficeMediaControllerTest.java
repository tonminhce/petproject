package com.shop.mediaservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security matrix + wire contract for /api/v1/backoffice/medias (spec D3):
 * class @PreAuthorize ADMIN, multipart POST → 201 created / 200
 * duplicate:true (dedup passthrough), DELETE → soft delete with 404/409
 * MED-code propagation. Multipart goes through the real MVC multipart
 * resolution — the network-layer proof lives in {@code MediaControllerIT}.
 */
@WebMvcTest(value = BackofficeMediaController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeMediaControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean MediaUploadService uploadService;
    @MockitoBean MediaLifecycleService lifecycleService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID MEDIA_ID = UUID.fromString("b1000000-0000-0000-0000-000000000001");

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007003"))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000007001"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    }

    private MediaResponse response(boolean duplicate) {
        return new MediaResponse(MEDIA_ID, "a".repeat(64), "image/jpeg", 4,
                "/api/v1/medias/" + MEDIA_ID, List.of(), duplicate);
    }

    // --- security matrix ---

    @Test
    void upload_noAuth_returns401() throws Exception {
        mockMvc.perform(multipart("/api/v1/backoffice/medias").file(file()))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(uploadService, lifecycleService);
    }

    @Test
    void upload_customerRole_returns403() throws Exception {
        mockMvc.perform(multipart("/api/v1/backoffice/medias").file(file()).with(customer()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(uploadService, lifecycleService);
    }

    @Test
    void delete_noAuth_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/medias/{id}", MEDIA_ID))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(lifecycleService);
    }

    @Test
    void delete_customerRole_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/medias/{id}", MEDIA_ID).with(customer()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(lifecycleService);
    }

    // --- upload: 201 created / 200 duplicate ---

    @Test
    void upload_newMedia_viaRealMultipart_returns201Created() throws Exception {
        when(uploadService.upload(org.mockito.ArgumentMatchers.any()))
            .thenReturn(response(false));

        mockMvc.perform(multipart("/api/v1/backoffice/medias")
                    .file(file())
                    .with(admin()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(MEDIA_ID.toString()))
            .andExpect(jsonPath("$.data.sha256").value("a".repeat(64)))
            .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
            .andExpect(jsonPath("$.data.sizeBytes").value(4))
            .andExpect(jsonPath("$.data.canonicalPath").value("/api/v1/medias/" + MEDIA_ID))
            .andExpect(jsonPath("$.data.duplicate").value(false));
    }

    @Test
    void upload_duplicateMedia_viaRealMultipart_returns200WithDuplicateTrue() throws Exception {
        when(uploadService.upload(org.mockito.ArgumentMatchers.any()))
            .thenReturn(response(true));

        mockMvc.perform(multipart("/api/v1/backoffice/medias")
                    .file(file())
                    .with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(MEDIA_ID.toString()))
            .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    // --- delete: happy path + MED-code propagation ---

    @Test
    void delete_admin_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/medias/{id}", MEDIA_ID).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"));

        verify(lifecycleService).softDelete(MEDIA_ID);
    }

    @Test
    void delete_unknownMedia_propagates404Med12004() throws Exception {
        doThrow(BusinessException.of(ErrorCode.MEDIA_NOT_FOUND))
            .when(lifecycleService).softDelete(MEDIA_ID);

        mockMvc.perform(delete("/api/v1/backoffice/medias/{id}", MEDIA_ID).with(admin()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("MED-12004"));
    }

    @Test
    void delete_repeatDelete_propagates409Med12005() throws Exception {
        doThrow(BusinessException.of(ErrorCode.MEDIA_ALREADY_DELETED))
            .when(lifecycleService).softDelete(MEDIA_ID);

        mockMvc.perform(delete("/api/v1/backoffice/medias/{id}", MEDIA_ID).with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("MED-12005"));
    }
}
