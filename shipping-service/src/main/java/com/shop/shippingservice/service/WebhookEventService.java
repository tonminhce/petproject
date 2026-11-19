package com.shop.shippingservice.service;

import com.shop.shippingservice.entity.ShipmentEvent;

public interface WebhookEventService {

    void handle(String carrier, byte[] rawBody, String signature);

    void retry(ShipmentEvent event);
}
