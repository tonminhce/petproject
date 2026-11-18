package com.shop.orderservice.dto.request;

import java.util.UUID;

public record OrderCreateRequest(
    UUID cartId,
    String couponCode,
    String recipientName,
    String phoneNumber,
    String shippingAddress
) {
    public OrderCreateRequest(UUID cartId, String couponCode) {
        this(cartId, couponCode, null, null, null);
    }
}