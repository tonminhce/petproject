package com.shop.shippingservice.outbox;

import com.shop.shippingservice.entity.Shipment;

public interface ShippingEventPublisher {

    void publishDelivered(Shipment shipment, boolean autoDelivered);
}
