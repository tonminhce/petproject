package com.shop.orderservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ConfirmOrchestrationWebMvcTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;

    private final UUID adminId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void seedAuth() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
            .subject(adminId.toString()).claim("preferred_username", "admin").build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    private OrderResponse sampleConfirmed() {
        return new OrderResponse(orderId, UUID.randomUUID(), OrderStatus.CONFIRMED,
            List.of(), new BigDecimal("10.00"), new BigDecimal("1.00"), BigDecimal.ZERO,
            new BigDecimal("11.00"), null, Instant.now(), Instant.now(), null, null, null);
    }

    @Test
    void confirm_withoutHeader_passesNullKeyAndAdminIdFromJwt() throws Exception {
        when(orderService.confirmOrder(eq(orderId), eq(adminId), isNull()))
            .thenReturn(sampleConfirmed());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // admin id resolved from the JWT subject (AuthenticatedUser.requireCurrent().id())
        verify(orderService).confirmOrder(orderId, adminId, null);
    }

    @Test
    void confirm_sameKeyReplay_returnsSameBody() throws Exception {
        when(orderService.confirmOrder(eq(orderId), eq(adminId), eq("key-1")))
            .thenReturn(sampleConfirmed());

        String first = mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // envelope timestamp differs per call by design — the replayed PAYLOAD must be identical
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(second).get("data"))
            .isEqualTo(mapper.readTree(first).get("data"));
        verify(orderService, times(2)).confirmOrder(orderId, adminId, "key-1");
    }

    @Test
    void confirm_coordinatorFailure_returns409Ord4011() throws Exception {
        when(orderService.confirmOrder(eq(orderId), eq(adminId), isNull()))
            .thenThrow(BusinessException.of(ErrorCode.CONFIRM_COMMIT_FAILED, orderId));

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ORD-4011"));
    }
}
