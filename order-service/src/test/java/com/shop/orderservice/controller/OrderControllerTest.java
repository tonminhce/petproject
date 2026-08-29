package com.shop.orderservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.service.OrderService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void seedAuth() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
            .subject(userId.toString()).claim("preferred_username", "alice").build();
        // FIX: 2-arg ctor with Collections.emptyList() authorities — 1-arg ctor leaves authenticated=false
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    private OrderResponse sampleOrder() {
        var item = new OrderItemResponse(UUID.randomUUID(), "Product", 3,
            new BigDecimal("29.99"), new BigDecimal("89.97"));
        return new OrderResponse(UUID.randomUUID(), userId, OrderStatus.PENDING,
            List.of(item), new BigDecimal("89.97"), new BigDecimal("7.20"),
            new BigDecimal("0"), new BigDecimal("97.17"), null,
            Instant.now(), null, null, null, null);
    }

    @Test
    void createOrder_returns200WithIdempotencyKey() throws Exception {
        when(orderService.createOrder(any(UUID.class), any(), any())).thenReturn(sampleOrder());

        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", "abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"couponCode\":\"SUMMER20\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Order created successfully"));
    }

    @Test
    void findMyOrders_returns200() throws Exception {
        when(orderService.findMyOrders(any(UUID.class), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/orders/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void findById_returns200() throws Exception {
        when(orderService.findById(eq(orderId), any(UUID.class), any(Boolean.class)))
            .thenReturn(sampleOrder());

        mockMvc.perform(get("/api/v1/orders/" + orderId))
            .andExpect(status().isOk());
    }

    @Test
    void cancelOrder_returns200() throws Exception {
        when(orderService.cancelOrder(eq(orderId), any(UUID.class), any(Boolean.class)))
            .thenReturn(sampleOrder());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk());
    }

    @Test
    void createOrder_worksWithoutIdempotencyKey() throws Exception {
        when(orderService.createOrder(any(UUID.class), any(), any())).thenReturn(sampleOrder());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    void createOrder_rejectsIdempotencyKeyOver64Chars() throws Exception {
        // idempotency_keys.key is varchar(64) — a longer header must be a 400 from
        // the guard, not a DB constraint violation (review M5).
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", "k".repeat(65))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }
}
