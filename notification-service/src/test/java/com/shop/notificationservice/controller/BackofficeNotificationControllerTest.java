package com.shop.notificationservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.response.NotificationResponse;
import com.shop.notificationservice.service.NotificationService;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BackofficeNotificationController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeNotificationControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean NotificationService notificationService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID notificationId = UUID.fromString("00000000-0000-0000-0000-000000009001");
    private final UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000009002");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000009003");
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000009004");

    private NotificationResponse sampleResponse() {
        return new NotificationResponse(
            notificationId, eventId, "ORDER_CONFIRMED", orderId, userId,
            NotificationStatus.SENT, NotificationChannel.SMTP, "Order confirmed",
            Instant.parse("2026-08-30T08:00:00Z"));
    }

    // --- ADMIN happy paths ---

    @Test
    void list_admin_returns200WithPagedNotifications() throws Exception {
        when(notificationService.findAllByOrderId(isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/notifications")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(notificationId.toString()))
            .andExpect(jsonPath("$.data.content[0].eventId").value(eventId.toString()))
            .andExpect(jsonPath("$.data.content[0].eventType").value("ORDER_CONFIRMED"))
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.content[0].status").value("SENT"))
            .andExpect(jsonPath("$.data.content[0].channel").value("SMTP"))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(notificationService).findAllByOrderId(isNull(), eq(PageRequest.of(0, 10)));
    }

    @Test
    void get_admin_returns200WithNotification() throws Exception {
        when(notificationService.findById(notificationId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/backoffice/notifications/{id}", notificationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(notificationId.toString()))
            .andExpect(jsonPath("$.data.eventId").value(eventId.toString()))
            .andExpect(jsonPath("$.data.eventType").value("ORDER_CONFIRMED"))
            .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.userId").value(userId.toString()))
            .andExpect(jsonPath("$.data.status").value("SENT"))
            .andExpect(jsonPath("$.data.channel").value("SMTP"))
            .andExpect(jsonPath("$.data.subject").value("Order confirmed"))
            .andExpect(jsonPath("$.data.createdAt").value("2026-08-30T08:00:00Z"));
    }

    @Test
    void list_adminWithOrderIdFilter_passesPageRequestToService() throws Exception {
        when(notificationService.findAllByOrderId(orderId, PageRequest.of(1, 5)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(1, 5), 11));

        mockMvc.perform(get("/api/v1/backoffice/notifications")
                .param("orderId", orderId.toString())
                .param("page", "1")
                .param("size", "5")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.totalElements").value(11));

        verify(notificationService).findAllByOrderId(orderId, PageRequest.of(1, 5));
    }

    // --- security matrix ---

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/notifications"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/notifications/{id}", notificationId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/notifications")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/notifications/{id}", notificationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    // --- business error-code mapping ---

    @Test
    void get_unknownNotification_returns404WithNtf9001() throws Exception {
        when(notificationService.findById(notificationId))
            .thenThrow(BusinessException.of(ErrorCode.NOTIFICATION_NOT_FOUND, notificationId));

        mockMvc.perform(get("/api/v1/backoffice/notifications/{id}", notificationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("NTF-9001"));
    }
}
