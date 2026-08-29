package com.shop.orderservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CartItemAddRequest(
    @NotNull UUID productId,
    @NotNull @Min(1) @Max(99) Integer quantity
) {}