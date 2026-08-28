package com.shop.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record InventoryUpsertRequest(
    @NotNull UUID productId,
    @NotNull @Min(0) Integer availableQuantity
) {}
