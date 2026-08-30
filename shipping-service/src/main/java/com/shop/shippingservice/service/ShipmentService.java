package com.shop.shippingservice.service;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.OrderLifecycleEvent;
import com.shop.shippingservice.dto.response.ShipmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ShipmentService {

    void handleOrderEvent(OrderLifecycleEvent event);

    Page<ShipmentResponse> findAll(ShipmentStatus status, Carrier carrier, UUID orderId, Pageable pageable);

    ShipmentResponse findById(UUID id);

    ShipmentResponse assignTracking(UUID id, String trackingNumber);

    ShipmentResponse transition(UUID id, ShipmentStatus status);

    ShipmentResponse fail(UUID id);

    ShipmentResponse retry(UUID id);
}
