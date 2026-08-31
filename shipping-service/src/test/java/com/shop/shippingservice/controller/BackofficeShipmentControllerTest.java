package com.shop.shippingservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.response.ShipmentResponse;
import com.shop.shippingservice.service.ShipmentService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BackofficeShipmentController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeShipmentControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ShipmentService shipmentService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID shipmentId = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-00000000a002");

    private ShipmentResponse sampleResponse(ShipmentStatus status) {
        return new ShipmentResponse(
            shipmentId, orderId, Carrier.MANUAL, "TRK-0001", status, ShipmentStatus.CREATED,
            false, Instant.parse("2026-08-30T10:00:00Z"), null, 3L,
            Instant.parse("2026-08-29T08:00:00Z"));
    }

    // --- ADMIN happy paths ---

    @Test
    void list_admin_returns200WithPagedShipments() throws Exception {
        when(shipmentService.findAll(isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse(ShipmentStatus.IN_TRANSIT)),
                PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/shipments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(shipmentId.toString()))
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.content[0].carrier").value("MANUAL"))
            .andExpect(jsonPath("$.data.content[0].trackingNumber").value("TRK-0001"))
            .andExpect(jsonPath("$.data.content[0].status").value("IN_TRANSIT"))
            .andExpect(jsonPath("$.data.content[0].previousStatus").value("CREATED"))
            .andExpect(jsonPath("$.data.content[0].autoDelivered").value(false))
            .andExpect(jsonPath("$.data.content[0].lastCarrierUpdate").value("2026-08-30T10:00:00Z"))
            .andExpect(jsonPath("$.data.content[0].version").value(3))
            .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-08-29T08:00:00Z"))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(shipmentService).findAll(isNull(), isNull(), isNull(), eq(PageRequest.of(0, 10)));
    }

    @Test
    void get_admin_returns200WithShipment() throws Exception {
        when(shipmentService.findById(shipmentId)).thenReturn(sampleResponse(ShipmentStatus.CREATED));

        mockMvc.perform(get("/api/v1/backoffice/shipments/{id}", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(shipmentId.toString()))
            .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.carrier").value("MANUAL"))
            .andExpect(jsonPath("$.data.trackingNumber").value("TRK-0001"))
            .andExpect(jsonPath("$.data.status").value("CREATED"))
            .andExpect(jsonPath("$.data.createdAt").value("2026-08-29T08:00:00Z"));
    }

    @Test
    void tracking_admin_returns200WithPickedUpShipment() throws Exception {
        when(shipmentService.assignTracking(shipmentId, "TRK-MANUAL-42"))
            .thenReturn(sampleResponse(ShipmentStatus.PICKED_UP));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/tracking", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trackingNumber\": \"TRK-MANUAL-42\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(shipmentId.toString()))
            .andExpect(jsonPath("$.data.status").value("PICKED_UP"));

        verify(shipmentService).assignTracking(shipmentId, "TRK-MANUAL-42");
    }

    @Test
    void transition_admin_returns200WithAdvancedShipment() throws Exception {
        when(shipmentService.transition(shipmentId, ShipmentStatus.IN_TRANSIT))
            .thenReturn(sampleResponse(ShipmentStatus.IN_TRANSIT));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/transition", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"IN_TRANSIT\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(shipmentId.toString()))
            .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"));

        verify(shipmentService).transition(shipmentId, ShipmentStatus.IN_TRANSIT);
    }

    @Test
    void fail_admin_returns200WithFailedShipment() throws Exception {
        when(shipmentService.fail(shipmentId))
            .thenReturn(sampleResponse(ShipmentStatus.DELIVERY_FAILED));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/fail", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("DELIVERY_FAILED"));

        verify(shipmentService).fail(shipmentId);
    }

    @Test
    void retry_admin_returns200WithInTransitShipment() throws Exception {
        when(shipmentService.retry(shipmentId))
            .thenReturn(sampleResponse(ShipmentStatus.IN_TRANSIT));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/retry", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"));

        verify(shipmentService).retry(shipmentId);
    }

    // --- filter params routed to service ---

    @Test
    void list_adminWithStatusFilter_passesFiltersToService() throws Exception {
        when(shipmentService.findAll(eq(ShipmentStatus.IN_TRANSIT), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse(ShipmentStatus.IN_TRANSIT)),
                PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/shipments")
                .param("status", "IN_TRANSIT")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].status").value("IN_TRANSIT"));

        verify(shipmentService).findAll(eq(ShipmentStatus.IN_TRANSIT), isNull(), isNull(),
            eq(PageRequest.of(0, 10)));
    }

    @Test
    void list_adminWithCarrierFilter_passesFiltersToService() throws Exception {
        when(shipmentService.findAll(isNull(), eq(Carrier.MANUAL), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse(ShipmentStatus.CREATED)),
                PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/shipments")
                .param("carrier", "MANUAL")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].carrier").value("MANUAL"));

        verify(shipmentService).findAll(isNull(), eq(Carrier.MANUAL), isNull(), eq(PageRequest.of(0, 10)));
    }

    @Test
    void list_adminWithOrderIdFilter_passesFilterToService() throws Exception {
        when(shipmentService.findAll(isNull(), isNull(), eq(orderId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse(ShipmentStatus.CREATED)),
                PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/shipments")
                .param("orderId", orderId.toString())
                .param("page", "2")
                .param("size", "20")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(shipmentService).findAll(isNull(), isNull(), eq(orderId), eq(PageRequest.of(2, 20)));
    }

    // --- security matrix ---

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/shipments"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/shipments/{id}", shipmentId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tracking_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/tracking", shipmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trackingNumber\": \"TRK-MANUAL-42\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void transition_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/transition", shipmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"IN_TRANSIT\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/shipments")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/shipments/{id}", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void tracking_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/tracking", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trackingNumber\": \"TRK-MANUAL-42\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void transition_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/transition", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"IN_TRANSIT\"}"))
            .andExpect(status().isForbidden());
    }

    // --- business error-code mapping ---

    @Test
    void get_unknownShipment_returns404WithShp10001() throws Exception {
        when(shipmentService.findById(shipmentId))
            .thenThrow(BusinessException.of(ErrorCode.SHIPMENT_NOT_FOUND, shipmentId));

        mockMvc.perform(get("/api/v1/backoffice/shipments/{id}", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("SHP-10001"));
    }

    @Test
    void tracking_blankTrackingNumber_returns400WithShp10005() throws Exception {
        when(shipmentService.assignTracking(shipmentId, ""))
            .thenThrow(BusinessException.of(ErrorCode.TRACKING_REQUIRED));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/tracking", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trackingNumber\": \"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("SHP-10005"));
    }

    @Test
    void transition_illegalTransition_returns409WithShp10003() throws Exception {
        when(shipmentService.transition(shipmentId, ShipmentStatus.DELIVERED))
            .thenThrow(BusinessException.of(ErrorCode.SHIPMENT_INVALID_TRANSITION,
                ShipmentStatus.CREATED, ShipmentStatus.DELIVERED));

        mockMvc.perform(post("/api/v1/backoffice/shipments/{id}/transition", shipmentId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"DELIVERED\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("SHP-10003"));
    }
}
