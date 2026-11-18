package com.shop.inventoryservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import com.shop.common.security.service.ServiceCallerGuard;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = InventoryController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class, ServiceCallerGuard.class})
class ReservationStateEndpointTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean ReservationService reservationService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ReservationResponse sampleResponse() {
        Instant now = Instant.now();
        return new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING,
            now.minusSeconds(60), now.plusSeconds(840), null, null, null);
    }

    @Test
    void state_serviceRole_returns200WithAllTimestampFields() throws Exception {
        when(inventoryService.getState(reservationId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/inventory/reservations/{reservationId}/state", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client").claim("azp", "order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()))
            .andExpect(jsonPath("$.data.productId").value(productId.toString()))
            .andExpect(jsonPath("$.data.quantity").value(5))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.reservedAt").exists())
            .andExpect(jsonPath("$.data.expiresAt").exists())
            .andExpect(jsonPath("$.data.committedAt").doesNotExist())
            .andExpect(jsonPath("$.data.releasedAt").doesNotExist());
    }

    @Test
    void state_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/reservations/{reservationId}/state", reservationId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void state_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/reservations/{reservationId}/state", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void state_unknownId_returns404WithInv3003() throws Exception {
        when(inventoryService.getState(reservationId))
            .thenThrow(BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));

        mockMvc.perform(get("/api/v1/inventory/reservations/{reservationId}/state", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client").claim("azp", "order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INV-3003"));
    }
}
