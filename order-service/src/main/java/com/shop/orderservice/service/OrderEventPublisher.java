package com.shop.orderservice.service;

import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;

import java.util.List;

public interface OrderEventPublisher {
    void publishCreated(Order order, List<OrderItem> items);
    void publishStatusChanged(Order order);
    void publishCancelled(Order order);
}