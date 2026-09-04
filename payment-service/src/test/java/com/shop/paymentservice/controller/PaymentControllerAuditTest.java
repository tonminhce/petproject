package com.shop.paymentservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.service.PaymentService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

/**
 * Audit spot matrix (spec D6, fix round 2 + R3 veto): the ADMIN-only refund
 * lifecycle endpoint and the SERVICE-gated create mutation exercised through
 * the {@code AuditAspect} — the service-token case proves machine actors
 * resolve to {@code clientId}/{@code azp} with actor type {@code service}.
 */
@WebMvcTest(value = PaymentController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class PaymentControllerAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentService paymentService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    @Test
    void refund_emitsAuditLineWithAnnotatedActionAndResourceId() throws Exception {
        UUID paymentId = UUID.fromString("00000000-0000-0000-0000-00000000d001");
        when(paymentService.refund(paymentId))
            .thenReturn(PaymentResponse.from(Payment.builder()
                .id(paymentId)
                .orderId(UUID.fromString("00000000-0000-0000-0000-00000000d002"))
                .amount(new BigDecimal("49.90"))
                .currency("EUR")
                .status(PaymentStatus.REFUNDED)
                .previousStatus(PaymentStatus.CAPTURED)
                .provider("stripe")
                .idempotencyKey("idem-123")
                .receiptKey("receipt-123")
                .build()));

        mockMvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000d003"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("payment.refund");
        assertThat(event.resourceType()).isEqualTo("payment");
        assertThat(event.resourceId()).isEqualTo(paymentId.toString());
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"payment.refund\"",
            "\"resourceType\":\"payment\"",
            "\"resourceId\":\"" + paymentId + "\"");
    }

    @Test
    void create_byServiceToken_emitsAuditLineWithServiceActor() throws Exception {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-00000000d004");
        when(paymentService.create(any(CreatePaymentRequest.class)))
            .thenReturn(PaymentResponse.from(Payment.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-00000000d005"))
                .orderId(orderId)
                .amount(new BigDecimal("49.90"))
                .currency("EUR")
                .status(PaymentStatus.CAPTURED)
                .provider("stripe")
                .idempotencyKey("idem-456")
                .build()));

        mockMvc.perform(post("/api/v1/payments")
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-00000000d006")
                        .claim("azp", "checkout-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"orderId":"%s","amount":49.90,"currency":"EUR","idempotencyKey":"idem-456"}"""
                    .formatted(orderId)))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("payment.create");
        assertThat(event.resourceType()).isEqualTo("payment");
        assertThat(event.actorType()).isEqualTo("service");
        assertThat(event.actorId()).isEqualTo("checkout-service");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"payment.create\"",
            "\"actor\":{\"id\":\"checkout-service\",\"type\":\"service\"}");
    }
}
