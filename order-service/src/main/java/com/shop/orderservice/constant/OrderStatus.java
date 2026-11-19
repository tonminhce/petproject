package com.shop.orderservice.constant;

/**
 * Order lifecycle states. Transitions are validated centrally by
 * {@code OrderStatusServiceImpl} — never mutate {@code Order.status} directly
 * without going through {@code validateTransition}.
 */
public enum OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
}
