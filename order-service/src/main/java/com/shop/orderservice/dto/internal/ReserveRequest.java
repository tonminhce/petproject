package com.shop.orderservice.dto.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ReserveRequest(
    @NotNull @Positive Integer quantity,
    UUID orderId
) {}
