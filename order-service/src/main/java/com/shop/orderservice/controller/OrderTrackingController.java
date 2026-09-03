package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.response.OrderTrackingResponse;
import com.shop.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
public class OrderTrackingController {

    private final OrderService orderService;

    @GetMapping("/track")
    public ApiResponse<OrderTrackingResponse> trackOrder(
            @RequestParam UUID orderId,
            @RequestParam String phone) {
        return ApiResponse.ok(
                orderService.trackOrder(orderId, phone),
                "Order tracking details retrieved successfully");
    }
}
