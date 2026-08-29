package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record PromotionReserveResponse(UUID reservationId, BigDecimal discountAmount, BigDecimal finalAmount) {}
