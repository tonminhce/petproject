package com.shop.inventoryservice.controller;

import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.security.service.ServiceCallerGuard;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
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

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the internal reservation-count endpoint (F-060).
 *
 * <p>The endpoint is how the catalogue learns that a product still has live
 * stock reservations before it soft-deletes the product. It runs with the
 * REAL security chain, so the SERVICE-role gate is actually enforced: SERVICE
 * role → 200, anonymous → 401, USER role → 403.</p>
 */
@WebMvcTest(value = InternalInventoryController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class, ServiceCallerGuard.class})
class InternalInventoryControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    void activeReservations_serviceRole_returns200WithTheCount() throws Exception {
        when(inventoryService.activeReservationCount(productId)).thenReturn(2L);

        mockMvc.perform(get("/internal/inventory/reservations/{productId}", productId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client").claim("azp", "product-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.productId").value(productId.toString()))
            .andExpect(jsonPath("$.data.activeReservationCount").value(2));
    }

    @Test
    void activeReservations_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/internal/inventory/reservations/{productId}", productId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void activeReservations_userRole_returns403() throws Exception {
        mockMvc.perform(get("/internal/inventory/reservations/{productId}", productId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }
}
