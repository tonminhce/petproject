package com.shop.ratingservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.ratingservice.dto.request.RatingEditRequest;
import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.service.RatingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = StorefrontRatingController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class StorefrontRatingControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean RatingService ratingService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000004001");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000004002");
    private final UUID ratingId = UUID.fromString("00000000-0000-0000-0000-000000004003");

    private RatingResponse response() {
        return new RatingResponse(ratingId, productId, userId, 5,
            "Great product, really enjoyed it", true, false, null, Instant.parse("2026-08-31T10:00:00Z"));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject(userId.toString()))
            .authorities(createAuthorityList("ROLE_USER"));
    }

    private String submitBody() {
        return """
            {"productId":"%s","rating":5,"comment":"Great product, really enjoyed it"}""".formatted(productId);
    }

    private String editBody(int rating) {
        return """
            {"rating":%d,"comment":"Edited comment, still decent value"}""".formatted(rating);
    }

    // --- anonymous ---

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/ratings").param("productId", productId.toString()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void submit_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/ratings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody()))
            .andExpect(status().isUnauthorized());
    }

    // --- list ---

    @Test
    void list_returns200WithPageMapping() throws Exception {
        when(ratingService.findVisibleByProductId(productId, 0, 20))
            .thenReturn(new PageImpl<>(List.of(response()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/ratings")
                .param("productId", productId.toString())
                .with(customer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(ratingId.toString()))
            .andExpect(jsonPath("$.data.content[0].productId").value(productId.toString()))
            .andExpect(jsonPath("$.data.content[0].rating").value(5))
            .andExpect(jsonPath("$.data.content[0].verified").value(true))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(ratingService).findVisibleByProductId(productId, 0, 20);
    }

    // --- submit ---

    @Test
    void submit_returns201WithRating() throws Exception {
        when(ratingService.submit(eq(userId), any(RatingSubmitRequest.class))).thenReturn(response());

        mockMvc.perform(post("/api/v1/ratings")
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(ratingId.toString()))
            .andExpect(jsonPath("$.data.verified").value(true));

        verify(ratingService).submit(eq(userId), any(RatingSubmitRequest.class));
    }

    // --- edit ---

    @Test
    void edit_returns200WithUpdatedRating() throws Exception {
        RatingResponse edited = new RatingResponse(ratingId, productId, userId, 2,
            "Edited comment, still decent value", true, false,
            Instant.parse("2026-08-31T11:00:00Z"), Instant.parse("2026-08-31T10:00:00Z"));
        when(ratingService.edit(eq(userId), eq(productId), any(RatingEditRequest.class))).thenReturn(edited);

        mockMvc.perform(put("/api/v1/ratings/{productId}", productId)
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.rating").value(2))
            .andExpect(jsonPath("$.data.editedAt").isNotEmpty());

        verify(ratingService).edit(eq(userId), eq(productId), any(RatingEditRequest.class));
    }

    @Test
    void edit_ratingOutOfBounds_returns400WithErr0422V() throws Exception {
        mockMvc.perform(put("/api/v1/ratings/{productId}", productId)
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(0)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"))
            .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(ratingService);
    }

    @Test
    void edit_noOwnRow_returns404WithRtg11002() throws Exception {
        when(ratingService.edit(eq(userId), eq(productId), any(RatingEditRequest.class)))
            .thenThrow(BusinessException.of(ErrorCode.RATING_NOT_FOUND));

        mockMvc.perform(put("/api/v1/ratings/{productId}", productId)
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(editBody(2)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("RTG-11002"));
    }
}
