package com.shop.orderservice.service;

import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;

import java.util.UUID;

public interface CartService {
    CartResponse getMyCart(UUID userId);
    CartResponse addItem(UUID userId, CartItemAddRequest request);
    CartResponse updateItem(UUID userId, UUID cartItemId, CartItemUpdateRequest request);
    void removeItem(UUID userId, UUID cartItemId);
    void clearCart(UUID userId);
}
