package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.constant.OrderStatus;
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
    // P2-6 — deliberately NOT @PreAuthorize("hasRole('USER')"): Keycloak users may
    // lack an explicit USER realm role, which would surface as an unhelpful 403. The
    // filter chain already enforces authentication (class level); the service-layer
    // owner check ensures users only ever touch their own orders.
    public ApiResponse<OrderResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // The idempotency_keys.key column is varchar(64) — reject longer headers with
        // a 400 instead of a DB constraint violation (review M5).
        if (idempotencyKey != null && idempotencyKey.length() > 64) {
            throw BusinessException.badRequest("order.idempotency.key.tooLong", 64);
        }
        return ApiResponse.ok(
            orderService.createOrder(currentUserId(), request, idempotencyKey),
            "Order created successfully");
    }

    @GetMapping("/me")
    public ApiResponse<PageResponse<OrderResponse>> findMyOrders(
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<OrderResponse> result = orderService.findMyOrders(currentUserId(), pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<OrderResponse>> findAll(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
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

    @GetMapping("/verify-purchase")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<PageResponse<OrderItemResponse>> verifyPurchase(
            @RequestParam UUID userId, @RequestParam UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<OrderItemResponse> result =
            orderService.findDeliveredItemsByUserAndProduct(userId, productId, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    private static UUID currentUserId() {
        return UUID.fromString(AuthenticatedUser.requireCurrent().id());
    }
}
