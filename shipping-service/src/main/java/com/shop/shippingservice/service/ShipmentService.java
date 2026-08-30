package com.shop.shippingservice.service;

import com.shop.shippingservice.dto.OrderLifecycleEvent;

public interface ShipmentService {

    void handleOrderEvent(OrderLifecycleEvent event);
}
