package com.shop.inventoryservice.dto.response;

import com.shop.inventoryservice.entity.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID reservationId,
    UUID productId,
    Integer quantity,
    ReservationStatus status,
    Instant expiresAt,
    UUID orderId
) {}
