package com.shop.promotionservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record ReserveRequest(
    @NotNull UUID userId,
    @NotNull UUID orderId,
    @NotNull @Positive BigDecimal orderAmount
) {}
