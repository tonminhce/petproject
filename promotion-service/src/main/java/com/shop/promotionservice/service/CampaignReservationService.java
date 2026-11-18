package com.shop.promotionservice.service;

import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;

import java.util.UUID;

public interface CampaignReservationService {

    ReservationResponse reserve(String code, ReserveRequest request);

    void commit(UUID id);

    void release(UUID id);

    void releaseCommitted(UUID id);

    ReservationResponse getState(UUID id);
}
