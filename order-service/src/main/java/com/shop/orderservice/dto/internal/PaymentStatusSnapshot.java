package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentStatusSnapshot(
        UUID orderId,
        String status,
        UUID id,
        BigDecimal amount,
        String currency,
        BigDecimal refundedAmount,
        String refundedReference) {}
