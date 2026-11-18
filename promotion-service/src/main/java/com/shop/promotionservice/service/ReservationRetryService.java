package com.shop.promotionservice.service;

import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;

import java.util.UUID;

public interface ReservationRetryService {

    ReservationResponse reserveWithRetry(String code, ReserveRequest request);

    void commitWithRetry(UUID reservationId);

    void releaseWithRetry(UUID reservationId);

    void releaseCommittedWithRetry(UUID reservationId);

    ReservationResponse getStateWithRetry(UUID reservationId);
}
