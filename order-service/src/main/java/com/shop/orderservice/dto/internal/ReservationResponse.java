package com.shop.orderservice.dto.internal;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID reservationId,
    UUID productId,
    Integer quantity,
    Instant expiresAt
) {}
