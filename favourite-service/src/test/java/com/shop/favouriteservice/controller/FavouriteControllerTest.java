package com.shop.favouriteservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.service.FavouriteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FavouriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class FavouriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private FavouriteService favouriteService;

    private final UUID userId = UUID.randomUUID();
    private final UUID favouriteId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void seedSecurityContext() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("preferred_username", "alice")
                .build();
        // Use the 2-arg ctor — the 1-arg ctor leaves authenticated=false, which
        // makes AuthenticatedUser.current() return Optional.empty.
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private FavouriteResponse sampleResponse() {
        return new FavouriteResponse(favouriteId, userId, productId, Instant.now());
    }

    @Test
    void findAll_returns200WithPagedEnvelope() throws Exception {
        when(favouriteService.findAllByCurrentUser(any(UUID.class), any(Pageable.class)))
                .thenReturn(PageResponse.of(List.of(sampleResponse()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/favourites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(favouriteId.toString()))
                .andExpect(jsonPath("$.data.content[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void findAll_rejectsMalformedSubjectWith401() throws Exception {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("not-a-uuid")
                .claim("preferred_username", "alice")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList()));

        mockMvc.perform(get("/api/v1/favourites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ERR-0401"));
    }

    @Test
    void findById_returns200WithEnvelope() throws Exception {
        when(favouriteService.findById(eq(favouriteId), any(UUID.class))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/favourites/" + favouriteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(favouriteId.toString()));
    }

    @Test
    void create_returns200WithCreatedEnvelope() throws Exception {
        when(favouriteService.create(any(UUID.class), any(FavouriteCreateRequest.class)))
                .thenReturn(sampleResponse());

        FavouriteCreateRequest req = new FavouriteCreateRequest(productId);
        mockMvc.perform(post("/api/v1/favourites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Favourite added successfully"))
                .andExpect(jsonPath("$.data.id").value(favouriteId.toString()));
    }

    @Test
    void create_returns400_whenProductIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/favourites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("productId")));
    }

    @Test
    void deleteById_returns200WithMessageEnvelope() throws Exception {
        mockMvc.perform(delete("/api/v1/favourites/" + favouriteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Favourite removed successfully"));

        verify(favouriteService).deleteById(eq(favouriteId), any(UUID.class));
    }

    @Test
    void deleteByProduct_returns200WithMessageEnvelope() throws Exception {
        mockMvc.perform(delete("/api/v1/favourites/by-product/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Favourite removed successfully"));

        verify(favouriteService).deleteByProductId(any(UUID.class), eq(productId));
    }
}
