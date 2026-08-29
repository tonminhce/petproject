package com.shop.orderservice.dto.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Plain record (NOT @Builder — records have canonical constructor).
 * Construct via {@code new ReserveRequest(quantity, orderId)}.
 */
public record ReserveRequest(
    @NotNull @Positive Integer quantity,
    UUID orderId  // populated by OrderServiceImpl — null when called from other paths
) {}
