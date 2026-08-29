package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSnapshot(UUID productId, String title, BigDecimal unitPrice) {}
