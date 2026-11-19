package com.shop.promotionservice.service;

import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;

/**
 * Seam for promotion lifecycle events ({@code promotion.reserved.v1},
 * {@code promotion.committed.v1}, ...). Task 6 ships a logging no-op;
 * Task 10 replaces it with the transactional outbox publisher.
 */
public interface PromotionEventPublisher {

    void publishReserved(Campaign campaign, CouponUsageReservation reservation);

    void publishCommitted(Campaign campaign, CouponUsageReservation reservation);

    /** {@code previousStatus} is "PENDING" (reserve→release) or "COMMITTED" (confirm-flow rollback). */
    void publishReleased(Campaign campaign, CouponUsageReservation reservation, String previousStatus);
}
