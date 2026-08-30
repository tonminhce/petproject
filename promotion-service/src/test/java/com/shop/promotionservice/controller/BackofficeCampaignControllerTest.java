package com.shop.promotionservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.response.CampaignResponse;
import com.shop.promotionservice.dto.response.CampaignUsageResponse;
import com.shop.promotionservice.service.CampaignService;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the backoffice campaign CRUD endpoints (spec §4.2).
 *
 * <p>Mirrors {@link PromotionReservationControllerTest}: REAL security chain from
 * common-security ({@code SecurityAutoConfiguration} + resource-server chain), so
 * the class-level ADMIN gate is actually enforced — anonymous → 401, SERVICE/USER
 * → 403, ADMIN → through. JWTs seeded via {@code jwt()}; {@link JwtDecoder} mocked
 * (no JWKS fetch). Error assertions on stable error-code strings only.
 */
@WebMvcTest(value = BackofficeCampaignController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class BackofficeCampaignControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean CampaignService campaignService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID campaignId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private final UUID usageUserId = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private final UUID usageOrderId = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

    private CampaignResponse sampleResponse() {
        return new CampaignResponse(
            campaignId, "SAVE10", "Save 10%", "PERCENT", new BigDecimal("10.00"),
            new BigDecimal("50.00"),
            Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
            100, new BigDecimal("1000.00"), 1, CampaignStatus.ACTIVE,
            Instant.parse("2026-08-29T08:00:00Z"), Instant.parse("2026-08-29T08:00:00Z"));
    }

    private CampaignUsageResponse sampleUsage() {
        return new CampaignUsageResponse(
            reservationId, usageUserId, usageOrderId,
            new BigDecimal("199.99"), new BigDecimal("20.00"), UsageStatus.COMMITTED,
            Instant.parse("2026-08-30T09:00:00Z"), Instant.parse("2026-08-30T09:05:00Z"), null);
    }

    private String campaignBody() {
        return """
            {"code": "SAVE10", "name": "Save 10%", "discountType": "PERCENT",
             "discountValue": 10.00, "minOrderAmount": 50.00,
             "startsAt": "2026-08-01T00:00:00Z", "endsAt": "2026-09-01T00:00:00Z",
             "maxRedemptions": 100, "totalBudget": 1000.00, "perUserLimit": 1,
             "status": "ACTIVE"}
            """;
    }

    // --- ADMIN happy paths ---

    @Test
    void list_admin_returns200WithPagedCampaigns() throws Exception {
        when(campaignService.findAll(isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(campaignId.toString()))
            .andExpect(jsonPath("$.data.content[0].code").value("SAVE10"))
            .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void list_adminWithStatusFilter_passesStatusToService() throws Exception {
        when(campaignService.findAll(eq(CampaignStatus.ACTIVE), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/promotions").param("status", "ACTIVE")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].id").value(campaignId.toString()));

        verify(campaignService).findAll(eq(CampaignStatus.ACTIVE), any(Pageable.class));
    }

    @Test
    void get_admin_returns200WithCampaign() throws Exception {
        when(campaignService.findById(campaignId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/backoffice/promotions/{id}", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(campaignId.toString()))
            .andExpect(jsonPath("$.data.code").value("SAVE10"))
            .andExpect(jsonPath("$.data.discountValue").value(10.00))
            .andExpect(jsonPath("$.data.perUserLimit").value(1));
    }

    @Test
    void create_admin_returns200WithCreatedCampaign() throws Exception {
        when(campaignService.create(eq(new com.shop.promotionservice.dto.request.CampaignRequest(
                "SAVE10", "Save 10%", "PERCENT", new BigDecimal("10.00"),
                new BigDecimal("50.00"),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                100, new BigDecimal("1000.00"), 1, CampaignStatus.ACTIVE))))
            .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(campaignId.toString()))
            .andExpect(jsonPath("$.data.code").value("SAVE10"));
    }

    @Test
    void update_admin_returns200WithUpdatedCampaign() throws Exception {
        when(campaignService.update(eq(campaignId), any(
                com.shop.promotionservice.dto.request.CampaignRequest.class)))
            .thenReturn(new CampaignResponse(
                campaignId, "SAVE10", "Renamed", "PERCENT", new BigDecimal("15.00"),
                null, null, null, null, null, 1, CampaignStatus.INACTIVE,
                null, null));

        mockMvc.perform(put("/api/v1/backoffice/promotions/{id}", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Renamed"))
            .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void delete_admin_returns200WithMessage() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/promotions/{id}", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Campaign deleted successfully"));

        verify(campaignService).delete(campaignId);
    }

    @Test
    void usages_admin_returns200WithUsageRows() throws Exception {
        when(campaignService.usages(eq(campaignId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleUsage()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/backoffice/promotions/{id}/usages", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].reservationId").value(reservationId.toString()))
            .andExpect(jsonPath("$.data.content[0].userId").value(usageUserId.toString()))
            .andExpect(jsonPath("$.data.content[0].orderId").value(usageOrderId.toString()))
            .andExpect(jsonPath("$.data.content[0].status").value("COMMITTED"))
            .andExpect(jsonPath("$.data.content[0].reservedAt").exists());
    }

    // --- security matrix ---

    @Test
    void list_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/promotions"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_serviceRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void delete_serviceRole_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/backoffice/promotions/{id}", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
    }

    // --- validation (400 ERR-0422-V) ---

    @Test
    void create_missingCode_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Save 10%", "discountType": "PERCENT", "discountValue": 10.00}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void create_lowercaseDiscountType_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code": "SAVE10", "name": "Save 10%", "discountType": "percent",
                     "discountValue": 10.00}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void create_percentZeroDiscountValue_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code": "SAVE10", "name": "Save 10%", "discountType": "PERCENT",
                     "discountValue": 0}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    // --- business error-code mapping ---

    @Test
    void create_duplicateCode_returns409WithPro7002() throws Exception {
        when(campaignService.create(any(com.shop.promotionservice.dto.request.CampaignRequest.class)))
            .thenThrow(BusinessException.of(ErrorCode.CAMPAIGN_ALREADY_EXISTS, "SAVE10"));

        mockMvc.perform(post("/api/v1/backoffice/promotions")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PRO-7002"));
    }

    @Test
    void get_unknownCampaign_returns404WithPro7001() throws Exception {
        when(campaignService.findById(campaignId))
            .thenThrow(BusinessException.of(ErrorCode.CAMPAIGN_NOT_FOUND, campaignId));

        mockMvc.perform(get("/api/v1/backoffice/promotions/{id}", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PRO-7001"));
    }

    @Test
    void delete_campaignInUse_returns409WithPro7003() throws Exception {
        doThrow(BusinessException.of(ErrorCode.CAMPAIGN_IN_USE, "SAVE10"))
            .when(campaignService).delete(campaignId);

        mockMvc.perform(delete("/api/v1/backoffice/promotions/{id}", campaignId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("admin"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PRO-7003"));
    }
}
