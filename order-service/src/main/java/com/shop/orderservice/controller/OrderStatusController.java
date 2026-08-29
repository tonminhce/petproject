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

/**
 * Admin / service-to-service status transitions. Authorization is endpoint-level
 * ({@code SERVICE or ADMIN}); the service layer only enforces the state machine,
 * so no per-call role inspection is needed here.
 */
@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public class OrderStatusController {

    private final OrderService orderService;

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderResponse> confirm(@PathVariable UUID orderId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID adminId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(orderService.confirmOrder(orderId, adminId, idempotencyKey));
    }

    @PostMapping("/{orderId}/ship")
    public ApiResponse<OrderResponse> ship(@PathVariable UUID orderId) {
        return ApiResponse.ok(orderService.shipOrder(orderId));
    }

    @PostMapping("/{orderId}/deliver")
    public ApiResponse<OrderResponse> deliver(@PathVariable UUID orderId) {
        return ApiResponse.ok(orderService.deliverOrder(orderId));
    }
}