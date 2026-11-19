package com.shop.mediaservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.mediaservice.service.MediaQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URL;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security + wire contract for the public read surface (spec D3): GET is
 * P2-6 (no @PreAuthorize — chain enforces authentication) and answers 302
 * with the presigned Location; HEAD is the existence check WITHOUT presign.
 * The full presign matrix (variant × format) verifies controller→service
 * forwarding; resolution logic itself is unit-covered in
 * {@code MediaQueryServiceImplTest}.
 */
@WebMvcTest(value = MediaPublicController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class MediaPublicControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean MediaQueryService mediaQueryService;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID MEDIA_ID = UUID.fromString("c1000000-0000-0000-0000-000000000001");

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000009001"))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    // --- security matrix (P2-6: authenticated edge, no role requirement) ---

    @Test
    void get_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/medias/{id}", MEDIA_ID))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(mediaQueryService);
    }

    @Test
    void head_noAuth_returns401() throws Exception {
        mockMvc.perform(head("/api/v1/medias/{id}", MEDIA_ID))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(mediaQueryService);
    }

    // --- presign matrix: every variant × format → 302 Location ---

    @ParameterizedTest(name = "GET variant={0} format={1} → 302 Location presigned")
    @CsvSource({
            "display, auto,  display.webp",
            "display, webp,  display.webp",
            "thumb,   auto,  thumb.webp",
            "thumb,   webp,  thumb.webp",
            "original, auto, original.jpg",
            "original, webp, original.webp"})
    void get_presignMatrix_answers302WithLocation(String variant, String format, String objectPath)
            throws Exception {
        String expectedUrl = "http://minio.test:9000/media-bucket/" + MEDIA_ID + "/" + objectPath
                + "?X-Amz-Signature=sig";
        when(mediaQueryService.resolve(MEDIA_ID, variant, format)).thenReturn(new URL(expectedUrl));

        mockMvc.perform(get("/api/v1/medias/{id}", MEDIA_ID)
                    .queryParam("variant", variant)
                    .queryParam("format", format)
                    .with(customer()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl(expectedUrl));

        verify(mediaQueryService).resolve(MEDIA_ID, variant, format);
    }

    @Test
    void get_withoutParams_defaultsToDisplayAndAuto() throws Exception {
        String expectedUrl = "http://minio.test:9000/media-bucket/" + MEDIA_ID + "/display.webp?sig=1";
        when(mediaQueryService.resolve(MEDIA_ID, "display", "auto")).thenReturn(new URL(expectedUrl));

        mockMvc.perform(get("/api/v1/medias/{id}", MEDIA_ID).with(customer()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl(expectedUrl));

        verify(mediaQueryService).resolve(MEDIA_ID, "display", "auto");
    }

    // --- error propagation ---

    @Test
    void get_unknownVariant_propagates404Med12004() throws Exception {
        when(mediaQueryService.resolve(eq(MEDIA_ID), eq("hero"), any()))
            .thenThrow(BusinessException.of(ErrorCode.MEDIA_NOT_FOUND));

        mockMvc.perform(get("/api/v1/medias/{id}", MEDIA_ID)
                    .queryParam("variant", "hero")
                    .with(customer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("MED-12004"));
    }

    @Test
    void get_storageDown_propagates503Med12006() throws Exception {
        when(mediaQueryService.resolve(any(), any(), any()))
            .thenThrow(BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE));

        mockMvc.perform(get("/api/v1/medias/{id}", MEDIA_ID).with(customer()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("MED-12006"));
    }

    // --- HEAD: existence check WITHOUT presign ---

    @Test
    void head_existingMedia_answers200WithoutPresigning() throws Exception {
        when(mediaQueryService.exists(MEDIA_ID)).thenReturn(true);

        mockMvc.perform(head("/api/v1/medias/{id}", MEDIA_ID).with(customer()))
            .andExpect(status().isOk());

        verify(mediaQueryService).exists(MEDIA_ID);
        verify(mediaQueryService, never()).resolve(any(), any(), any());
    }

    @Test
    void head_unknownMedia_answers404() throws Exception {
        when(mediaQueryService.exists(MEDIA_ID)).thenReturn(false);

        mockMvc.perform(head("/api/v1/medias/{id}", MEDIA_ID).with(customer()))
            .andExpect(status().isNotFound());

        verify(mediaQueryService).exists(MEDIA_ID);
        verify(mediaQueryService, never()).resolve(any(), any(), any());
    }
}
