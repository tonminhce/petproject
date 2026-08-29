package com.shop.orderservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record CartItemResponse(
    UUID id,
    UUID productId,
    String productTitle,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {}