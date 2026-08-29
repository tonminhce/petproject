package com.shop.orderservice.service;

import com.shop.orderservice.constant.OrderStatus;

public interface OrderStatusService {
    void validateTransition(OrderStatus from, OrderStatus to);
}
