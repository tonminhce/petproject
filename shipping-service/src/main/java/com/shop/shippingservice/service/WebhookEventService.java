package com.shop.shippingservice.service;

public interface WebhookEventService {

    void handle(String carrier, byte[] rawBody, String signature);
}
