package com.shop.orderservice.dto.response;

import com.shop.orderservice.constant.OrderStatus;

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
    Instant cancelledAt,
    String recipientName,
    String phoneNumber,
    String shippingAddress
) {
    public OrderResponse(UUID id, UUID userId, OrderStatus status, List<OrderItemResponse> items,
                         BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount,
                         BigDecimal total, String couponCode, Instant createdAt,
                         Instant confirmedAt, Instant shippedAt, Instant deliveredAt, Instant cancelledAt) {
        this(id, userId, status, items, subtotal, taxAmount, discountAmount, total, couponCode,
             createdAt, confirmedAt, shippedAt, deliveredAt, cancelledAt, null, null, null);
    }
}