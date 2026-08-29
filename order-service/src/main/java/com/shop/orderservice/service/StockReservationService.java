package com.shop.orderservice.service;

import com.shop.orderservice.dto.internal.ReserveRequest;

import java.util.UUID;

public interface StockReservationService {
    UUID reserve(UUID productId, ReserveRequest request);
    void release(UUID reservationId);
}
