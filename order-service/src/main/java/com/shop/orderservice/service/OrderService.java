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

    /**
     * Confirm a PENDING order (admin / service-to-service). {@code actor} is the
     * caller label resolved by token shape (H-6): ADMIN → sub, SERVICE →
     * {@code service:<azp>} — stored verbatim on idempotency rows so machine
     * callers are never misattributed to a service-account UUID.
     */
    OrderResponse confirmOrder(UUID orderId, String actor, String idempotencyKey);
    OrderResponse shipOrder(UUID orderId);
    OrderResponse deliverOrder(UUID orderId);

    OrderResponse findById(UUID orderId, UUID userId, boolean isAdmin);

    Page<OrderResponse> findMyOrders(UUID userId, Pageable pageable);
    Page<OrderResponse> findAll(OrderStatus status, Pageable pageable);

    /** SERVICE-facing rating-eligibility probe (rating-service epic Task 7). */
    Page<OrderItemResponse> findDeliveredItemsByUserAndProduct(UUID userId, UUID productId, Pageable pageable);
}
