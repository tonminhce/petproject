package com.shop.inventoryservice.service;

import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;

import java.util.UUID;

public interface ReservationService {

    ReservationResponse reserveWithRetry(UUID productId, ReserveRequest request);

    void commitWithRetry(UUID reservationId);

    void releaseWithRetry(UUID reservationId);

    void releaseCommittedWithRetry(UUID reservationId);
}
