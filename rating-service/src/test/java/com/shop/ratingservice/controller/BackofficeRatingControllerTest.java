package com.shop.ratingservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.ratingservice.dto.request.RatingHideRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.service.RatingService;
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

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BackofficeRatingController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeRatingControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean RatingService ratingService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000005001");
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000005002");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000005003");
    private final UUID ratingId = UUID.fromString("00000000-0000-0000-0000-000000005004");

    private RatingResponse response(boolean hidden) {
        return new RatingResponse(ratingId, productId, userId, 5,
            "Great product, really enjoyed it", true, hidden,
            Instant.parse("2026-08-31T10:00:00Z"), Instant.parse("2026-08-31T09:00:00Z"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(adminId.toString()))
            .authorities(createAuthorityList("ROLE_ADMIN"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject(userId.toString()))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    private String hideBody(String reason) {
        return """
            {"reason":"%s"}""".formatted(reason);
    }

    // --- ADMIN happy paths ---

    @Test
    void hide_admin_returns200WithHiddenRating() throws Exception {
        when(ratingService.hide(eq(ratingId), eq(adminId), eq("Abusive comment")))
            .thenReturn(response(true));

        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("Abusive comment")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(ratingId.toString()))
            .andExpect(jsonPath("$.data.hidden").value(true));

        verify(ratingService).hide(eq(ratingId), eq(adminId), eq("Abusive comment"));
    }

    @Test
    void unhide_admin_returns200WithVisibleRating() throws Exception {
        when(ratingService.unhide(eq(ratingId), eq(adminId))).thenReturn(response(false));

        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/unhide", ratingId)
                .with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(ratingId.toString()))
            .andExpect(jsonPath("$.data.hidden").value(false));

        verify(ratingService).unhide(eq(ratingId), eq(adminId));
    }

    // --- validation ---

    @Test
    void hide_blankReason_returns400WithErr0422V() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"))
            .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(ratingService);
    }

    @Test
    void hide_reasonOver500_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("x".repeat(501))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));

        verifyNoInteractions(ratingService);
    }

    // --- security matrix ---

    @Test
    void hide_customerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("Abusive comment")))
            .andExpect(status().isForbidden());

        verifyNoInteractions(ratingService);
    }

    @Test
    void unhide_customerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/unhide", ratingId)
                .with(customer()))
            .andExpect(status().isForbidden());

        verifyNoInteractions(ratingService);
    }

    @Test
    void hide_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("Abusive comment")))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(ratingService);
    }

    @Test
    void unhide_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/unhide", ratingId))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(ratingService);
    }

    // --- business error-code mapping ---

    @Test
    void hide_unknownRating_returns404WithRtg11002() throws Exception {
        when(ratingService.hide(eq(ratingId), eq(adminId), any(String.class)))
            .thenThrow(BusinessException.of(ErrorCode.RATING_NOT_FOUND));

        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("Abusive comment")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RTG-11002"));
    }

    @Test
    void hide_alreadyHidden_returns409WithRtg11003() throws Exception {
        when(ratingService.hide(eq(ratingId), eq(adminId), any(String.class)))
            .thenThrow(BusinessException.of(ErrorCode.RATING_ALREADY_HIDDEN));

        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/hide", ratingId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(hideBody("Abusive comment")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RTG-11003"));
    }

    @Test
    void unhide_notHidden_returns409WithRtg11004() throws Exception {
        when(ratingService.unhide(eq(ratingId), eq(adminId)))
            .thenThrow(BusinessException.of(ErrorCode.RATING_NOT_HIDDEN));

        mockMvc.perform(post("/api/v1/backoffice/ratings/{id}/unhide", ratingId)
                .with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RTG-11004"));
    }
}
