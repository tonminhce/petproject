package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    // ⚠️ P2-6 — DO NOT use @PreAuthorize("hasRole('USER')"): Keycloak users may not have
    // explicit realm role "USER" → 403 oan. Filter chain already authenticated (isAuthenticated()
    // at class level); service-layer owner check ensures users can only access their own orders.
    public ApiResponse<OrderResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(
            orderService.createOrder(currentUserId(), request, idempotencyKey),
            "Order created successfully");
    }

    @GetMapping("/me")
    public ApiResponse<PageResponse<OrderResponse>> findMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OrderResponse> result = orderService.findMyOrders(currentUserId(), pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<OrderResponse>> findAll(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OrderResponse> result = orderService.findAll(status, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> findById(@PathVariable UUID orderId) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.findById(orderId, userId, isAdmin));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancelOrder(@PathVariable UUID orderId) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.cancelOrder(orderId, userId, isAdmin), "Order cancelled");
    }

    private static UUID currentUserId() {
        return UUID.fromString(AuthenticatedUser.requireCurrent().id());
    }
}
