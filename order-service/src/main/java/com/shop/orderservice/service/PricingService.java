package com.shop.orderservice.service;

import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.entity.CartItem;

import java.util.List;
import java.util.UUID;

public interface PricingService {
    PricingBreakdown calculate(UUID userId, List<CartItem> items, String couponCode);
}
