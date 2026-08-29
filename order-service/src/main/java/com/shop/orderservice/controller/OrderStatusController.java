package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public class OrderStatusController {

    private final OrderService orderService;

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderResponse> confirm(@PathVariable UUID orderId) {
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.confirmOrder(orderId, isAdmin));
    }

    @PostMapping("/{orderId}/ship")
    public ApiResponse<OrderResponse> ship(@PathVariable UUID orderId) {
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.shipOrder(orderId, isAdmin));
    }

    @PostMapping("/{orderId}/deliver")
    public ApiResponse<OrderResponse> deliver(@PathVariable UUID orderId) {
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.deliverOrder(orderId, isAdmin));
    }
}