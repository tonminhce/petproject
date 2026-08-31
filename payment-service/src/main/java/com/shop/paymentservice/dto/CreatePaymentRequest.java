package com.shop.paymentservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(UUID orderId, BigDecimal amount, String currency, String idempotencyKey) {
}
