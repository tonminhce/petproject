package com.shop.paymentservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.paymentservice.constant.PaymentStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6, fix round 2): the ADMIN-only refund lifecycle
 * endpoint exercised through the {@code AuditAspect}. Note create/capture are
 * hasAnyRole('SERVICE','ADMIN') checkout machinery and are deliberately NOT
 * audited (same ruling as tax-rates/calculate).
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
            .thenReturn(Payment.builder()
                .id(paymentId)
                .orderId(UUID.fromString("00000000-0000-0000-0000-00000000d002"))
                .amount(new BigDecimal("49.90"))
                .currency("EUR")
                .status(PaymentStatus.REFUNDED)
                .previousStatus(PaymentStatus.CAPTURED)
                .provider("stripe")
                .idempotencyKey("idem-123")
                .receiptKey("receipt-123")
                .build());

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
}
