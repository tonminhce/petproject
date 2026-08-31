package com.shop.orderservice.service;

import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.shop.orderservice.constant.OrderStatus;

import java.util.UUID;

public interface OrderService {

    OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey);

    OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin);

    OrderResponse confirmOrder(UUID orderId, UUID adminUserId, String idempotencyKey);
    OrderResponse shipOrder(UUID orderId);
    OrderResponse deliverOrder(UUID orderId);

    OrderResponse findById(UUID orderId, UUID userId, boolean isAdmin);

    Page<OrderResponse> findMyOrders(UUID userId, Pageable pageable);
    Page<OrderResponse> findAll(OrderStatus status, Pageable pageable);

    /** SERVICE-facing rating-eligibility probe (rating-service epic Task 7). */
    Page<OrderItemResponse> findDeliveredItemsByUserAndProduct(UUID userId, UUID productId, Pageable pageable);
}
