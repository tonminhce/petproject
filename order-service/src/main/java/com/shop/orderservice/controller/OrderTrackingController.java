package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
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
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String phone) {
        if ((token == null || token.isBlank()) && (phone == null || phone.isBlank())) {

            throw BusinessException.badRequest("order.track.credentials_required");
        }
        return ApiResponse.ok(
                orderService.trackOrder(orderId, token, phone),
                "Order tracking details retrieved successfully");
    }
}
