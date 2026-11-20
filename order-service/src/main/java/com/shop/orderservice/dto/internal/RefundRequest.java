package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;

public record RefundRequest(BigDecimal amount, String currency) {}
