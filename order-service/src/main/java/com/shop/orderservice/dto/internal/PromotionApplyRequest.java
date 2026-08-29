package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record PromotionApplyRequest(String code, BigDecimal orderAmount, UUID userId) {}
