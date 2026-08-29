package com.shop.orderservice.service;

import com.shop.orderservice.entity.OrderStatus;

public interface OrderStatusService {
    void validateTransition(OrderStatus from, OrderStatus to);
}
