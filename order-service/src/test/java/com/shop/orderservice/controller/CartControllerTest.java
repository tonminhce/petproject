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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // --- F-028: the guest cart id is a bearer capability ---

    @Test
    void mergeCart_returns200_whenTheIdIsPresentedInTheHeader() throws Exception {
        when(cartService.mergeCart(any(UUID.class), any(UUID.class))).thenReturn(sampleCart());
        String guestCartId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/carts/merge")
                .header("X-Guest-Cart-Id", guestCartId)
                .param("guestCartId", guestCartId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void mergeCart_returns400_whenTheIdOnlyComesFromTheQueryString() throws Exception {
        // A capability that came out of an access log, a proxy log, the
        // browser history or a Referer header is exactly what an attacker
        // would have. The header is what proves the caller holds it.
        mockMvc.perform(post("/api/v1/carts/merge")
                .param("guestCartId", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            // the rejection has to say WHICH rule was broken, otherwise a
            // different refusal (the mismatch rule) would pass this test
            // while the header rule was silently gone.
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("must be sent in the X-Guest-Cart-Id header")));

        verify(cartService, never()).mergeCart(any(UUID.class), any(UUID.class));
    }

    @Test
    void mergeCart_returns400_whenTheQueryStringIdDoesNotMatchTheHeader() throws Exception {
        mockMvc.perform(post("/api/v1/carts/merge")
                .header("X-Guest-Cart-Id", UUID.randomUUID().toString())
                .param("guestCartId", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("does not match the X-Guest-Cart-Id header")));

        verify(cartService, never()).mergeCart(any(UUID.class), any(UUID.class));
    }

    @Test
    void mergeCart_returns400_whenTheGuestCartIdIsNotAUuid() throws Exception {
        // A short or sequential id would make every guest cart in the shop
        // enumerable to any caller who guessed the next value.
        mockMvc.perform(post("/api/v1/carts/merge")
                .header("X-Guest-Cart-Id", "1"))
            .andExpect(status().isBadRequest());

        verify(cartService, never()).mergeCart(any(UUID.class), any(UUID.class));
    }

    @Test
    void guestCartEndpoints_return400_whenTheGuestCartIdIsNotAUuid() throws Exception {
        // the guest path only applies to an ANONYMOUS caller; an
        // authenticated one is served from its own cart and the guest id is
        // ignored.
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/v1/carts/me")
                .header("X-Guest-Cart-Id", "42"))
            .andExpect(status().isBadRequest());

        verify(cartService, never()).getMyCart(any(UUID.class));
    }

    @Test
    void guestCartEndpoints_serveAUuidGuestCart() throws Exception {
        SecurityContextHolder.clearContext();
        when(cartService.getMyCart(any(UUID.class))).thenReturn(sampleCart());

        mockMvc.perform(get("/api/v1/carts/me")
                .header("X-Guest-Cart-Id", UUID.randomUUID().toString()))
            .andExpect(status().isOk());
    }
}
