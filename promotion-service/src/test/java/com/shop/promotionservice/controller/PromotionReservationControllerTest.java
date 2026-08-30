package com.shop.promotionservice.controller;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.service.ReservationRetryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the promotion reservation endpoints (spec §4.1).
 *
 * <p>Mirrors inventory {@code ReservationStateEndpointTest} (hardening epic):
 * exercises the REAL security chain imported from {@code common-security}
 * ({@code SecurityAutoConfiguration} → {@code @EnableMethodSecurity} +
 * resource-server chain) so the SERVICE/ADMIN role gate is actually enforced:
 * SERVICE role → 2xx, anonymous → 401, USER role → 403 (@PreAuthorize).
 *
 * <p>JWTs are seeded via {@code SecurityMockMvcRequestPostProcessors.jwt()};
 * the {@link JwtDecoder} is mocked so no JWKS fetch happens at context startup.
 * Error assertions use the stable error-code string, never the localized message.
 */
@WebMvcTest(value = PromotionReservationController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class})
class PromotionReservationControllerTest {

    /**
     * The @WebMvcTest slice does not include Boot's security autoconfiguration,
     * so {@code HttpSecurity} (needed by {@code BaseSecurityConfig}) would be
     * missing. {@code @EnableWebSecurity} supplies it — matching what Boot
     * registers in the production app.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ReservationRetryService reservationService;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID campaignId = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private ReservationResponse sampleResponse() {
        return new ReservationResponse(
            reservationId, campaignId, "SUMMER10",
            new BigDecimal("10.00"), new BigDecimal("90.00"),
            "PENDING", Instant.now().plusSeconds(900));
    }

    private String reserveBody() {
        return """
            {"userId": "%s", "orderId": "%s", "orderAmount": 100.00}
            """.formatted(userId, reservationId);
    }

    @Test
    void reserve_serviceRole_returnsOkWithReservation() throws Exception {
        when(reservationService.reserveWithRetry("SUMMER10",
                new ReserveRequest(userId, reservationId, new BigDecimal("100.00"))))
            .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/promotions/{code}/reserve", "SUMMER10")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()))
            .andExpect(jsonPath("$.data.campaignId").value(campaignId.toString()))
            .andExpect(jsonPath("$.data.code").value("SUMMER10"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.expiresAt").exists());
    }

    @Test
    void commit_serviceRole_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/promotions/reservations/{reservationId}/commit", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("Reservation committed successfully"));

        verify(reservationService).commitWithRetry(reservationId);
    }

    @Test
    void release_serviceRole_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/promotions/reservations/{reservationId}/release", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("Reservation released successfully"));

        verify(reservationService).releaseWithRetry(reservationId);
    }

    @Test
    void releaseCommitted_serviceRole_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/promotions/reservations/{reservationId}/release-committed", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data").doesNotExist());

        verify(reservationService).releaseCommittedWithRetry(reservationId);
    }

    @Test
    void state_serviceRole_returns200WithReservationFields() throws Exception {
        when(reservationService.getStateWithRetry(reservationId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/promotions/reservations/{reservationId}/state", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()))
            .andExpect(jsonPath("$.data.campaignId").value(campaignId.toString()))
            .andExpect(jsonPath("$.data.code").value("SUMMER10"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.expiresAt").exists());
    }

    @Test
    void reserve_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/promotions/{code}/reserve", "SUMMER10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void state_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/promotions/reservations/{reservationId}/state", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("alice"))
                    .authorities(createAuthorityList("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void reserve_missingUserId_returns400WithValidationCode() throws Exception {
        mockMvc.perform(post("/api/v1/promotions/{code}/reserve", "SUMMER10")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"orderId": "%s", "orderAmount": 100.00}
                    """.formatted(reservationId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ERR-0422-V"));
    }

    @Test
    void reserve_perUserLimitExceeded_returns409WithPro7006() throws Exception {
        when(reservationService.reserveWithRetry("SUMMER10",
                new ReserveRequest(userId, reservationId, new BigDecimal("100.00"))))
            .thenThrow(BusinessException.of(ErrorCode.PER_USER_LIMIT_EXCEEDED, 3L));

        mockMvc.perform(post("/api/v1/promotions/{code}/reserve", "SUMMER10")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PRO-7006"));
    }

    @Test
    void commit_unknownReservation_returns404WithPro7008() throws Exception {
        doThrow(BusinessException.of(ErrorCode.PROMOTION_RESERVATION_NOT_FOUND, reservationId))
            .when(reservationService).commitWithRetry(reservationId);

        mockMvc.perform(post("/api/v1/promotions/reservations/{reservationId}/commit", reservationId)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject("service-client"))
                    .authorities(createAuthorityList("ROLE_SERVICE"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PRO-7008"));
    }
}
