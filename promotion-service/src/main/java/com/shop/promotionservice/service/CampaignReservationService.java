package com.shop.promotionservice.service;

import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;

import java.util.UUID;

/**
 * Transactional campaign reservation operations (spec §5.1/§5.3).
 * Callers go through {@code ReservationRetryService} — this interface is the
 * transactional boundary the retry loop re-invokes.
 */
public interface CampaignReservationService {

    ReservationResponse reserve(String code, ReserveRequest request);

    /** PENDING → COMMITTED; COMMITTED retry OK; terminal-wrong-way → PRO-7010; expired pending → PRO-7009 (§5.3). */
    void commit(UUID id);

    /** PENDING → RELEASED; RELEASED/EXPIRED retry OK; COMMITTED → PRO-7010 (§5.3). */
    void release(UUID id);

    /** Half-commit rollback: COMMITTED → RELEASED; terminal retry OK; PENDING → PRO-7010 (§5.3). */
    void releaseCommitted(UUID id);

    /** Read-only state projection for reconciliation polling. */
    ReservationResponse getState(UUID id);
}
