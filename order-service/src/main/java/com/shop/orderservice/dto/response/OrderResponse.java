package com.shop.orderservice.dto.response;

import com.shop.orderservice.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    UUID userId,
    OrderStatus status,
    List<OrderItemResponse> items,
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal discountAmount,
    BigDecimal total,
    String couponCode,
    Instant createdAt,
    Instant confirmedAt,
    Instant shippedAt,
    Instant deliveredAt,
    Instant cancelledAt
) {}