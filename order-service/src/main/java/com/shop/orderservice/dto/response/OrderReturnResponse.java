package com.shop.orderservice.dto.response;

import com.shop.orderservice.constant.ReturnStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderReturnResponse(
    UUID id,
    UUID orderId,
    UUID userId,
    String reason,
    String description,
    ReturnStatus status,
    BigDecimal refundAmount,
    String adminNotes,
    String reviewedBy,
    Instant reviewedAt,
    Instant createdAt
) {}
