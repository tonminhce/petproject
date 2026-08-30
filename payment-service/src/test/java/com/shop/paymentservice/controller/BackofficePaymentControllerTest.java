package com.shop.paymentservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.PaymentResponse;
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
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BackofficePaymentController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficePaymentControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentService paymentService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000009001");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000009003");

    private PaymentResponse sampleResponse() {
        return new PaymentResponse(
            paymentId, orderId, new BigDecimal("98.00"), "USD",
            PaymentStatus.CAPTURED, PaymentStatus.PENDING, "mock", "rcpt_12345",
            Instant.parse("2026-08-30T08:00:00Z"));
    }

    // --- ADMIN happy paths ---

    @Test
    void list_admin_returns200WithPagedPayments() throws Exception {
        when(paymentService.findAllByOrderId(isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(paymentId.toString()))
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.content[0].amount").value(98.00))
            .andExpect(jsonPath("$.data.content[0].currency").value("USD"))
            .andExpect(jsonPath("$.data.content[0].status").value("CAPTURED"))
            .andExpect(jsonPath("$.data.content[0].previousStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.content[0].provider").value("mock"))
            .andExpect(jsonPath("$.data.content[0].receiptKey").value("rcpt_12345"))
            .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-08-30T08:00:00Z"))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(paymentService).findAllByOrderId(isNull(), eq(PageRequest.of(0, 10)));
    }

    @Test
    void get_admin_returns200WithPayment() throws Exception {
        when(paymentService.findById(paymentId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/backoffice/payments/{id}", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(paymentId.toString()))
            .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.amount").value(98.00))
            .andExpect(jsonPath("$.data.currency").value("USD"))
            .andExpect(jsonPath("$.data.status").value("CAPTURED"))
            .andExpect(jsonPath("$.data.previousStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.provider").value("mock"))
            .andExpect(jsonPath("$.data.receiptKey").value("rcpt_12345"))
            .andExpect(jsonPath("$.data.createdAt").value("2026-08-30T08:00:00Z"));
    }

    @Test
    void list_adminWithOrderIdFilter_passesPageRequestToService() throws Exception {
        when(paymentService.findAllByOrderId(orderId, PageRequest.of(1, 5)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(1, 5), 11));

        mockMvc.perform(get("/api/v1/backoffice/payments")
                .param("orderId", orderId.toString())
                .param("page", "1")
                .param("size", "5")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.totalElements").value(11));

        verify(paymentService).findAllByOrderId(orderId, PageRequest.of(1, 5));
        verify(paymentService, never()).findAllByOrderId(isNull(), any(Pageable.class));
    }

    @Test
    void list_admin_sizeAboveFleetMax_isCappedToMaxPageSize() throws Exception {
        when(paymentService.findAllByOrderId(isNull(), eq(PageRequest.of(0, 200))))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 200), 1));

        mockMvc.perform(get("/api/v1/backoffice/payments")
                .param("size", "500")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(200));

        verify(paymentService).findAllByOrderId(isNull(), eq(PageRequest.of(0, 200)));
    }

    // --- security matrix ---

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/payments"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/payments/{id}", paymentId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/payments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/payments/{id}", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    // --- business error-code mapping ---

    @Test
    void get_unknownPayment_returns404WithPay5002() throws Exception {
        when(paymentService.findById(paymentId))
            .thenThrow(BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId));

        mockMvc.perform(get("/api/v1/backoffice/payments/{id}", paymentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PAY-5002"));
    }
}
