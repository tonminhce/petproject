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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderTrackingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class OrderTrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private final UUID orderId = UUID.randomUUID();

    @Test
    void trackOrder_success() throws Exception {
        OrderTrackingResponse response = new OrderTrackingResponse(
                orderId, OrderStatus.CONFIRMED, "Alice", "123 Main St",
                BigDecimal.valueOf(100), Instant.now(), List.of());

        when(orderService.trackOrder(orderId, "0912345678")).thenReturn(response);

        mockMvc.perform(get("/api/v1/orders/track")
                        .param("orderId", orderId.toString())
                        .param("phone", "0912345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.recipientName").value("Alice"));
    }
}
