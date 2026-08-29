package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;

public record PromotionApplyResponse(BigDecimal discountAmount, BigDecimal finalAmount) {}
