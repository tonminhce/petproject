package com.shop.orderservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.response.OrderTrackingResponse;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Order tracking is unauthenticated by design - a customer who has not
 * signed in yet must be able to follow their order - so the gate has to
 * carry the weight: the order's tracking TOKEN (a random capability) or,
 * for callers that have no token yet, the phone number.
 */
@WebMvcTest(OrderTrackingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class OrderTrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private final UUID orderId = UUID.randomUUID();

    private OrderTrackingResponse response() {
        return new OrderTrackingResponse(
                orderId, OrderStatus.CONFIRMED, "Alice", "123 Main St",
                BigDecimal.valueOf(100), Instant.now(), List.of());
    }

    @Test
    void trackOrder_success_withPhone() throws Exception {
        when(orderService.trackOrder(eq(orderId), isNull(), eq("0912345678"))).thenReturn(response());

        mockMvc.perform(get("/api/v1/orders/track")
                        .param("orderId", orderId.toString())
                        .param("phone", "0912345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.recipientName").value("Alice"));
    }

    @Test
    void trackOrder_success_withTrackingToken() throws Exception {
        // The token is a random capability: it authorises the lookup on its
        // own, so no phone number is needed.
        String token = UUID.randomUUID().toString();
        when(orderService.trackOrder(eq(orderId), eq(token), isNull())).thenReturn(response());

        mockMvc.perform(get("/api/v1/orders/track")
                        .param("orderId", orderId.toString())
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()));
    }

    @Test
    void trackOrder_returns400_whenNeitherTokenNorPhoneIsGiven() throws Exception {
        mockMvc.perform(get("/api/v1/orders/track")
                        .param("orderId", orderId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trackOrder_returns404_whenTheTokenIsWrong() throws Exception {
        // A wrong token and a wrong phone produce the SAME error, so the
        // endpoint is not an oracle for which half was wrong.
        when(orderService.trackOrder(eq(orderId), any(), isNull()))
                .thenThrow(com.shop.common.core.exception.BusinessException.of(
                        com.shop.common.core.exception.ErrorCode.ORDER_NOT_FOUND, orderId));

        mockMvc.perform(get("/api/v1/orders/track")
                        .param("orderId", orderId.toString())
                        .param("token", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }
}
