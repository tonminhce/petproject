package com.shop.inventoryservice.dto.response;

import java.time.Instant;
import java.util.UUID;

public record InventoryResponse(
    UUID productId,
    Integer availableQuantity,
    Integer reservedQuantity,
    Instant lastUpdated,
    Integer safetyStockThreshold
) {
    public InventoryResponse(UUID productId, Integer availableQuantity, Integer reservedQuantity, Instant lastUpdated) {
        this(productId, availableQuantity, reservedQuantity, lastUpdated, 10);
    }
}
