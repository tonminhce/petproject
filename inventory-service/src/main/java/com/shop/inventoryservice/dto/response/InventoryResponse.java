package com.shop.inventoryservice.dto.response;

import java.time.Instant;
import java.util.UUID;

public record InventoryResponse(
    UUID productId,
    Integer availableQuantity,
    Integer reservedQuantity,
    Instant lastUpdated
) {}
