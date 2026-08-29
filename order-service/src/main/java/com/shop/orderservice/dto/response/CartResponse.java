package com.shop.orderservice.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
    UUID id,
    UUID userId,
    List<CartItemResponse> items,
    BigDecimal subtotal,
    Instant createdAt,
    Instant updatedAt
) {}