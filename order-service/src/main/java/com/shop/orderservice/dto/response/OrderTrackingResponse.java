package com.shop.orderservice.dto.response;

import com.shop.orderservice.constant.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderTrackingResponse(
    UUID orderId,
    OrderStatus status,
    String recipientName,
    String shippingAddress,
    BigDecimal total,
    Instant createdAt,
    List<OrderItemResponse> items
) {}
