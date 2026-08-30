package com.shop.promotionservice.service;

import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;

/**
 * Transactional campaign reservation operations (spec §5.1/§5.3).
 * Callers go through {@code ReservationRetryService} — this interface is the
 * transactional boundary the retry loop re-invokes.
 */
public interface CampaignReservationService {

    ReservationResponse reserve(String code, ReserveRequest request);
}
