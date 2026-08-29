package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;

public record TaxCalculateResponse(BigDecimal taxAmount, BigDecimal appliedRate) {}
