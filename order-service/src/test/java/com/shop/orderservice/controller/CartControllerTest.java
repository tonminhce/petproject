package com.shop.orderservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.dto.response.CartItemResponse;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.service.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CartController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class CartControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CartService cartService;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID cartItemId = UUID.randomUUID();

    @BeforeEach
    void seedAuth() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
            .subject(userId.toString())
            .claim("preferred_username", "alice").build();
        // FIX: 2-arg ctor with Collections.emptyList() authorities — 1-arg ctor leaves authenticated=false
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    private CartResponse sampleCart() {
        var item = new CartItemResponse(cartItemId, productId, "Test Product", 2,
            new BigDecimal("19.99"), new BigDecimal("39.98"));
        return new CartResponse(UUID.randomUUID(), userId, List.of(item),
            new BigDecimal("39.98"), Instant.now(), Instant.now());
    }

    @Test
    void getMyCart_returns200WithEnvelope() throws Exception {
        when(cartService.getMyCart(any(UUID.class))).thenReturn(sampleCart());

        mockMvc.perform(get("/api/v1/carts/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.userId").value(userId.toString()))
            .andExpect(jsonPath("$.data.items[0].productId").value(productId.toString()));
    }

    @Test
    void addItem_returns200() throws Exception {
        when(cartService.addItem(any(UUID.class), any())).thenReturn(sampleCart());

        mockMvc.perform(post("/api/v1/carts/me/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    void addItem_returns400_whenProductIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/carts/me/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateItem_returns200() throws Exception {
        when(cartService.updateItem(any(UUID.class), any(UUID.class), any())).thenReturn(sampleCart());

        mockMvc.perform(put("/api/v1/carts/me/items/" + cartItemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
            .andExpect(status().isOk());
    }

    @Test
    void removeItem_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/carts/me/items/" + cartItemId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void clearCart_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/carts/me"))
            .andExpect(status().isOk());
    }
}
