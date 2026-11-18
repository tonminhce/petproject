package com.shop.orderservice.service;

import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.dto.response.OrderTrackingResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OrderService {

    OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey);

    OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin);

    OrderResponse confirmOrder(UUID orderId, String actor, String idempotencyKey);
    OrderResponse shipOrder(UUID orderId);
    OrderResponse deliverOrder(UUID orderId);

    OrderResponse findById(UUID orderId, UUID userId, boolean isAdmin);

    Page<OrderResponse> findMyOrders(UUID userId, Pageable pageable);
    Page<OrderResponse> findAll(OrderStatus status, Pageable pageable);

    Page<OrderItemResponse> findDeliveredItemsByUserAndProduct(UUID userId, UUID productId, Pageable pageable);

    OrderTrackingResponse trackOrder(UUID orderId, String trackingToken, String phoneNumber);
}
