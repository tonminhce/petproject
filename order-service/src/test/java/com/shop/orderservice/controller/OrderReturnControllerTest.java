package com.shop.orderservice.controller;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.constant.ReturnStatus;
import com.shop.orderservice.dto.request.OrderReturnCreateRequest;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderReturnController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class OrderReturnControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderReturnService orderReturnService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID returnId = UUID.randomUUID();

    @BeforeEach
    void setUpSecurityContext() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("preferred_username", "test_user")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, Collections.emptyList()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestReturn_success() throws Exception {
        OrderReturnResponse response = new OrderReturnResponse(
                returnId, orderId, userId, "Damaged", "Broken item",
                ReturnStatus.REQUESTED, BigDecimal.valueOf(50), null, null, null, Instant.now());

        when(orderReturnService.requestReturn(eq(userId), eq(orderId), any(OrderReturnCreateRequest.class)))
                .thenReturn(response);

        String json = """
                {
                    "reason": "Damaged",
                    "description": "Broken item",
                    "refundAmount": 50.00
                }
                """;

        mockMvc.perform(post("/api/v1/orders/{orderId}/returns", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(returnId.toString()))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void findByOrderId_returnsList() throws Exception {
        OrderReturnResponse response = new OrderReturnResponse(
                returnId, orderId, userId, "Damaged", "Broken item",
                ReturnStatus.REQUESTED, BigDecimal.valueOf(50), null, null, null, Instant.now());

        when(orderReturnService.findByOrderId(userId, orderId)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/orders/{orderId}/returns", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(returnId.toString()));
    }

    @Test
    void findMyReturns_returnsPagedList() throws Exception {
        OrderReturnResponse response = new OrderReturnResponse(
                returnId, orderId, userId, "Damaged", "Broken item",
                ReturnStatus.REQUESTED, BigDecimal.valueOf(50), null, null, null, Instant.now());

        when(orderReturnService.findMyReturns(eq(userId), any()))
                .thenReturn(PageResponse.of(List.of(response), 0, 10, 1));

        mockMvc.perform(get("/api/v1/orders/returns/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(returnId.toString()));
    }
}
