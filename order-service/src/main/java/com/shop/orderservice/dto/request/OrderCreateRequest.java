package com.shop.orderservice.dto.request;

import java.util.UUID;

public record OrderCreateRequest(
    UUID cartId,    // nullable — server falls back to findByUserIdAndDeletedFalse
    String couponCode,  // nullable, optional
    String recipientName,
    String phoneNumber,
    String shippingAddress
) {
    public OrderCreateRequest(UUID cartId, String couponCode) {
        this(cartId, couponCode, null, null, null);
    }
}