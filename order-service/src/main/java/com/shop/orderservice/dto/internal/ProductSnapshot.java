package com.shop.orderservice.dto.internal;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSnapshot(UUID productId, String title, @JsonAlias("priceUnit") BigDecimal unitPrice) {}
