package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record TaxCalculateRequest(UUID taxClassId, String country, String postalCode, BigDecimal amount) {}
