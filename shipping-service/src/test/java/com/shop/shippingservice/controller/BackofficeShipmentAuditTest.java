package com.shop.shippingservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.response.ShipmentResponse;
import com.shop.shippingservice.service.ShipmentService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6): one mutating backoffice shipment endpoint
 * (the status transition) exercised through the {@code AuditAspect} — the
 * action names the MEANING ({@code shipment.transition}), not the mechanics.
 */
@WebMvcTest(value = BackofficeShipmentController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class BackofficeShipmentAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ShipmentService shipmentService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    private final UUID shipmentId = UUID.fromString("00000000-0000-0000-0000-00000000c001");

    @Test
    void transition_emitsAuditLineWithAnnotatedActionAndResourceId() throws Exception {
        when(shipmentService.transition(shipmentId, ShipmentStatus.IN_TRANSIT))
            .thenReturn(new ShipmentResponse(shipmentId,
                UUID.fromString("00000000-0000-0000-0000-00000000c002"),
                Carrier.GHN, null, ShipmentStatus.IN_TRANSIT, ShipmentStatus.CREATED,
                false, null, null, 1L, Instant.parse("2026-08-31T10:00:00Z")));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/transition", shipmentId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000c003"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"IN_TRANSIT"}"""))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("shipment.transition");
        assertThat(event.resourceType()).isEqualTo("shipment");
        assertThat(event.resourceId()).isEqualTo(shipmentId.toString());
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"shipment.transition\"",
            "\"resourceType\":\"shipment\"",
            "\"resourceId\":\"" + shipmentId + "\"");
    }
}
