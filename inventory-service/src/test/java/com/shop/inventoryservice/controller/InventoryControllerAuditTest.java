package com.shop.inventoryservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6 + R3 veto): the SERVICE-gated reservation
 * mutations exercised through the {@code AuditAspect} — machine actors on
 * client-credentials tokens (azp claim, no session marker) must land on the
 * audit line as actor type {@code service} with the client id, so stock
 * mutations keep a complete insider-threat trail.
 */
@WebMvcTest(value = InventoryController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class InventoryControllerAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean ReservationService reservationService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    @Test
    void reserve_byServiceToken_emitsAuditLineWithServiceActorAndResourceId() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-00000000e001");
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-00000000e002");
        when(reservationService.reserveWithRetry(eq(productId),
                any(com.shop.inventoryservice.dto.request.ReserveRequest.class)))
            .thenReturn(new ReservationResponse(
                UUID.fromString("00000000-0000-0000-0000-00000000e003"),
                productId, 2, ReservationStatus.PENDING,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z"), null, null, orderId));

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000e004")
                        .claim("azp", "order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"quantity":2,"orderId":"%s"}""".formatted(orderId)))
            .andExpect(status().isCreated());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("inventory.reserve");
        assertThat(event.resourceType()).isEqualTo("reservation");
        assertThat(event.resourceId()).isEqualTo(productId.toString());
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.actorId()).isEqualTo("order-service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"inventory.reserve\"",
            "\"actor\":{\"id\":\"order-service\",\"type\":\"service\"}");
    }

    @Test
    void commit_byServiceToken_emitsAuditLineWithReservationResourceId() throws Exception {
        UUID reservationId = UUID.fromString("00000000-0000-0000-0000-00000000e005");

        mockMvc.perform(post("/api/v1/inventory/reservations/{reservationId}/commit", reservationId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000e004")
                        .claim("azp", "order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("inventory.commit");
        assertThat(event.resourceType()).isEqualTo("reservation");
        assertThat(event.resourceId()).isEqualTo(reservationId.toString());
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains("\"action\":\"inventory.commit\"");
    }
}
