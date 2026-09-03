package com.shop.orderservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderReturnCreateRequest(
    @NotBlank(message = "Reason is required")
    String reason,

    String description,

    @NotNull(message = "Refund amount is required")
    @Positive(message = "Refund amount must be positive")
    BigDecimal refundAmount
) {}
