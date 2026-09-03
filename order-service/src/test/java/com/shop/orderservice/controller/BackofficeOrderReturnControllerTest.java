package com.shop.orderservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import com.shop.orderservice.service.OrderReturnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackofficeOrderReturnController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class BackofficeOrderReturnControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderReturnService orderReturnService;

    private final UUID returnId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUpSecurityContext() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("preferred_username", "admin")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reviewReturn_success() throws Exception {
        OrderReturnResponse response = new OrderReturnResponse(
                returnId, orderId, userId, "Damaged", "Broken item",
                ReturnStatus.REFUNDED, BigDecimal.valueOf(50), "Approved", "admin", Instant.now(), Instant.now());

        when(orderReturnService.reviewReturn(eq(returnId), eq("admin"), any(OrderReturnReviewRequest.class)))
                .thenReturn(response);

        String json = """
                {
                    "status": "APPROVED",
                    "adminNotes": "Approved"
                }
                """;

        mockMvc.perform(put("/api/v1/backoffice/returns/{returnId}/review", returnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(returnId.toString()))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));
    }
}
