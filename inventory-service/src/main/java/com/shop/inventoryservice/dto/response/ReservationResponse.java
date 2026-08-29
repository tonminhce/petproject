package com.shop.inventoryservice.dto.response;

import com.shop.inventoryservice.constant.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID reservationId,
    UUID productId,
    Integer quantity,
    ReservationStatus status,
    Instant reservedAt,
    Instant expiresAt,
    Instant committedAt,
    Instant releasedAt,
    UUID orderId
) {}
