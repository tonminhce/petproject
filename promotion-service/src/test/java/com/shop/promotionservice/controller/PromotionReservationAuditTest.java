package com.shop.promotionservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.service.ReservationRetryService;
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

import java.math.BigDecimal;
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
 * Audit spot matrix (spec D6 + R3 veto): the SERVICE-gated coupon-reservation
 * mutations exercised through the {@code AuditAspect} — machine actors land
 * as actor type {@code service} with the client id. The reserve route keys on
 * the {@code code} path variable, which by design (spec D6 — slugs/titles
 * never become resourceId) leaves {@code resourceId} null while the event
 * still records who reserved what code.
 */
@WebMvcTest(value = PromotionReservationController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class PromotionReservationAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ReservationRetryService reservationService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    @Test
    void reserve_byServiceToken_emitsAuditLineWithServiceActorAndNullResourceId() throws Exception {
        when(reservationService.reserveWithRetry(eq("WELCOME10"),
                any(com.shop.promotionservice.dto.request.ReserveRequest.class)))
            .thenReturn(new ReservationResponse(
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                UUID.fromString("00000000-0000-0000-0000-00000000a002"),
                "WELCOME10", new BigDecimal("5.00"), new BigDecimal("45.00"),
                "PENDING", Instant.parse("2026-01-01T00:15:00Z")));

        mockMvc.perform(post("/api/v1/promotions/{code}/reserve", "WELCOME10")
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000a003")
                        .claim("azp", "checkout-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":"00000000-0000-0000-0000-00000000a004",""" + """
                    "orderId":"00000000-0000-0000-0000-00000000a005","orderAmount":50.00}"""))
            .andExpect(status().isCreated());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("promotion.reserve");
        assertThat(event.resourceType()).isEqualTo("reservation");
        assertThat(event.resourceId()).isNull();
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.actorId()).isEqualTo("checkout-service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"promotion.reserve\"",
            "\"actor\":{\"id\":\"checkout-service\",\"type\":\"service\"}");
    }

    @Test
    void release_byServiceToken_emitsAuditLineWithReservationResourceId() throws Exception {
        UUID reservationId = UUID.fromString("00000000-0000-0000-0000-00000000a006");

        mockMvc.perform(post("/api/v1/promotions/reservations/{reservationId}/release", reservationId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000a003")
                        .claim("azp", "checkout-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("promotion.release");
        assertThat(event.resourceType()).isEqualTo("reservation");
        assertThat(event.resourceId()).isEqualTo(reservationId.toString());
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains("\"action\":\"promotion.release\"");
    }
}
