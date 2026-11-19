package com.shop.inventoryservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.security.service.ServiceCallerGuard;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.inventoryservice.constant.ReservationIdempotency;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, ServiceCallerGuard.class})
class InventoryControllerIdempotencyKeyTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean ReservationService reservationService;

    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @BeforeEach
    void authenticateOrderServiceCaller() {
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(orderServiceToken(), List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt orderServiceToken() {
        return Jwt.withTokenValue("inventory-idempotency-controller-test-token")
            .header("alg", "none")
            .subject("00000000-0000-0000-0000-00000000b001")
            .claim("azp", "order-service")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    }

    private ReservationResponse reservationResponse() {
        return new ReservationResponse(reservationId, productId, 2, ReservationStatus.PENDING,
            Instant.now(), Instant.now().plusSeconds(900), null, null, null);
    }

    @Test
    void reserveWithAnIdempotencyKeyHeader_forwardsTheKeyToTheService() throws Exception {
        when(reservationService.reserveWithRetry(eq(productId), any(ReserveRequest.class), eq("reserve-key-1")))
            .thenReturn(reservationResponse());

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                .header(ReservationIdempotency.KEY_HEADER, "reserve-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(new ReserveRequest(2, null))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()));

        verify(reservationService).reserveWithRetry(eq(productId), any(ReserveRequest.class), eq("reserve-key-1"));
        verify(reservationService, never()).reserveWithRetry(eq(productId), any(ReserveRequest.class));
    }

    @Test
    void reserveWithoutAnIdempotencyKeyHeader_usesTheKeylessReservePath() throws Exception {
        when(reservationService.reserveWithRetry(eq(productId), any(ReserveRequest.class)))
            .thenReturn(reservationResponse());

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(new ReserveRequest(2, null))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()));

        verify(reservationService).reserveWithRetry(eq(productId), any(ReserveRequest.class));
        verify(reservationService, never())
            .reserveWithRetry(eq(productId), any(ReserveRequest.class), any(String.class));
    }

    @Test
    void reserveWithAnIdempotencyKeyLongerThanTheLimit_isRejected() throws Exception {
        String tooLong = "k".repeat(ReservationIdempotency.MAX_KEY_LENGTH + 1);

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                .header(ReservationIdempotency.KEY_HEADER, tooLong)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(new ReserveRequest(2, null))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0400"));

        verify(reservationService, never())
            .reserveWithRetry(eq(productId), any(ReserveRequest.class), any(String.class));
    }
}
