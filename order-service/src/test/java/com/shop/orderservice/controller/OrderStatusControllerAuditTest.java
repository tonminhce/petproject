package com.shop.orderservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6 + R3 veto): the SERVICE-gated status
 * transitions (class-level {@code SERVICE or ADMIN} gate) exercised through
 * the {@code AuditAspect} — service-fulfillment actors must appear as actor
 * type {@code service} with the client id, giving the order lifecycle a
 * complete insider-threat trail.
 */
@WebMvcTest(value = OrderStatusController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class OrderStatusControllerAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    @Test
    void ship_byServiceToken_emitsAuditLineWithServiceActorAndOrderId() throws Exception {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-00000000f001");
        when(orderService.shipOrder(orderId))
            .thenReturn(new OrderResponse(orderId,
                UUID.fromString("00000000-0000-0000-0000-00000000f002"),
                OrderStatus.SHIPPED, List.of(), null, null, null, null,
                null, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/orders/{orderId}/ship", orderId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000f003")
                        .claim("azp", "fulfillment-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("order.ship");
        assertThat(event.resourceType()).isEqualTo("order");
        assertThat(event.resourceId()).isEqualTo(orderId.toString());
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.actorId()).isEqualTo("fulfillment-service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"order.ship\"",
            "\"actor\":{\"id\":\"fulfillment-service\",\"type\":\"service\"}");
    }

    @Test
    void deliver_byServiceToken_emitsAuditLineWithAnnotatedAction() throws Exception {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-00000000f004");
        when(orderService.deliverOrder(orderId))
            .thenReturn(new OrderResponse(orderId,
                UUID.fromString("00000000-0000-0000-0000-00000000f002"),
                OrderStatus.DELIVERED, List.of(), null, null, null, null,
                null, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/orders/{orderId}/deliver", orderId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000f003")
                        .claim("azp", "fulfillment-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("order.deliver");
        assertThat(event.resourceType()).isEqualTo("order");
        assertThat(event.resourceId()).isEqualTo(orderId.toString());
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains("\"action\":\"order.deliver\"");
    }
}
