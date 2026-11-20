package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record PromotionReserveRequest(String code, BigDecimal orderAmount, String currency, UUID userId, UUID orderId) {}
