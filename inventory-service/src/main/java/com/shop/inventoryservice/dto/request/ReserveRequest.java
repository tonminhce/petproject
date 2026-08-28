package com.shop.inventoryservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ReserveRequest(
    @NotNull @Positive Integer quantity,
    UUID orderId
) {}
