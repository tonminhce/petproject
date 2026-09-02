package com.shop.orderservice.service;

import com.shop.orderservice.dto.internal.ReserveRequest;

import java.util.UUID;

public interface StockReservationService {
    UUID reserve(UUID productId, ReserveRequest request);
    void release(UUID reservationId);

    /**
     * H9 — admin cancel of a CONFIRMED order must release the COMMITTED
     * reservation (restocking the inventory) so the goods return to the
     * available pool. Distinct from {@link #release(UUID)} which only
     * handles PENDING reservations.
     */
    void releaseCommitted(UUID reservationId);
}
