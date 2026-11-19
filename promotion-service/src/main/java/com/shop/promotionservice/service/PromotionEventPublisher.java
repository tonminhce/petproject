package com.shop.promotionservice.service;

import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;

public interface PromotionEventPublisher {

    void publishReserved(Campaign campaign, CouponUsageReservation reservation);

    void publishCommitted(Campaign campaign, CouponUsageReservation reservation);

    void publishReleased(Campaign campaign, CouponUsageReservation reservation, String previousStatus);
}
