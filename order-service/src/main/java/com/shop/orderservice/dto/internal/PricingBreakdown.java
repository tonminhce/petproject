package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record PricingBreakdown(
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal discountAmount,
    BigDecimal total,
    Map<UUID, ProductSnapshot> snapshots
) {}
