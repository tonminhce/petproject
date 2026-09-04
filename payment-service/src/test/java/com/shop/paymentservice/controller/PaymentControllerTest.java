package com.shop.paymentservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = PaymentController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class PaymentControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentService paymentService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000009001");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000009003");

    private PaymentResponse pendingPayment() {
        return PaymentResponse.from(Payment.builder()
            .id(paymentId)
            .orderId(orderId)
            .amount(new BigDecimal("98.00"))
            .currency("USD")
            .status(PaymentStatus.PENDING)
            .provider("mock")
            .idempotencyKey("idem-9001")
            .build());
    }

    private PaymentResponse capturedPayment() {
        return PaymentResponse.from(Payment.builder()
            .id(paymentId)
            .orderId(orderId)
            .amount(new BigDecimal("98.00"))
            .currency("USD")
            .status(PaymentStatus.CAPTURED)
            .previousStatus(PaymentStatus.PENDING)
            .provider("mock")
            .idempotencyKey("idem-9001")
            .receiptKey("rcpt_12345")
            .build());
    }

    private PaymentResponse capturedResponse() {
        return capturedPayment();
    }

    private String createBody() {
        return """
            {"orderId":"%s","amount":98.00,"currency":"USD","idempotencyKey":"idem-9001"}""".formatted(orderId);
    }

    // --- anonymous ---

    @Test
    void create_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void capture_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{id}/capture", paymentId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payments").param("orderId", orderId.toString()))
            .andExpect(status().isUnauthorized());
    }

    // --- USER denied ---

    @Test
    void create_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void refund_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/payments")
                .param("orderId", orderId.toString())
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    // --- SERVICE happy paths ---

    @Test
    void create_serviceRole_returns200WithPendingPayment() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class))).thenReturn(pendingPayment());

        mockMvc.perform(post("/api/v1/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(paymentId.toString()))
            .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.amount").value(98.00))
            .andExpect(jsonPath("$.data.currency").value("USD"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.provider").value("mock"));

        verify(paymentService).create(new CreatePaymentRequest(
            orderId, new BigDecimal("98.00"), "USD", "idem-9001"));
    }

    @Test
    void capture_serviceRole_returns200WithPayment() throws Exception {
        when(paymentService.capture(paymentId)).thenReturn(capturedPayment());

        mockMvc.perform(post("/api/v1/payments/{id}/capture", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(paymentId.toString()))
            .andExpect(jsonPath("$.data.status").value("CAPTURED"))
            .andExpect(jsonPath("$.data.previousStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.receiptKey").value("rcpt_12345"));

        verify(paymentService).capture(paymentId);
    }

    @Test
    void list_serviceRole_returns200WithOrderFilteredPage() throws Exception {
        when(paymentService.findAllByOrderId(orderId, PageRequest.of(0, 10)))
            .thenReturn(new PageImpl<>(List.of(capturedResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/payments")
                .param("orderId", orderId.toString())
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(paymentId.toString()))
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.content[0].status").value("CAPTURED"))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(paymentService).findAllByOrderId(eq(orderId), eq(PageRequest.of(0, 10)));
    }

    // --- refund is ADMIN-only ---

    @Test
    void refund_serviceRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
    }

    // --- ADMIN happy paths ---

    @Test
    void create_adminRole_returns200() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class))).thenReturn(pendingPayment());

        mockMvc.perform(post("/api/v1/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void refund_adminRole_returns200WithPayment() throws Exception {
        when(paymentService.refund(paymentId)).thenReturn(capturedPayment());

        mockMvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(paymentId.toString()))
            .andExpect(jsonPath("$.data.status").value("CAPTURED"));

        verify(paymentService).refund(paymentId);
    }

    @Test
    void list_adminRole_returns200() throws Exception {
        when(paymentService.findAllByOrderId(orderId, PageRequest.of(0, 10)))
            .thenReturn(new PageImpl<>(List.of(capturedResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/payments")
                .param("orderId", orderId.toString())
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // --- validation ---

    @Test
    void create_blankFields_returns400WithErr0422V() throws Exception {
        String body = """
            {"orderId":"%s","amount":98.00,"currency":"","idempotencyKey":""}""".formatted(orderId);

        mockMvc.perform(post("/api/v1/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"))
            .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void create_missingAmount_returns400WithErr0422V() throws Exception {
        String body = """
            {"orderId":"%s","currency":"USD","idempotencyKey":"idem-9001"}""".formatted(orderId);

        mockMvc.perform(post("/api/v1/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    // --- business error-code mapping ---

    @Test
    void capture_unknownPayment_returns404WithPay5002() throws Exception {
        when(paymentService.capture(paymentId))
            .thenThrow(BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId));

        mockMvc.perform(post("/api/v1/payments/{id}/capture", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PAY-5002"));
    }

    @Test
    void capture_notPending_returns409WithPay5004() throws Exception {
        when(paymentService.capture(paymentId))
            .thenThrow(BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, "CAPTURED"));

        mockMvc.perform(post("/api/v1/payments/{id}/capture", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("order-service"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAY-5004"));
    }

    @Test
    void refund_notCaptured_returns409WithPay5006() throws Exception {
        when(paymentService.refund(paymentId))
            .thenThrow(BusinessException.of(ErrorCode.REFUND_INVALID_STATE, "PENDING"));

        mockMvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAY-5006"));
    }
}
