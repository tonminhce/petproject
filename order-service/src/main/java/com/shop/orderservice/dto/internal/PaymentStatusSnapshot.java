package com.shop.orderservice.dto.internal;

import java.util.UUID;

public record PaymentStatusSnapshot(UUID orderId, String status, UUID id) {}
