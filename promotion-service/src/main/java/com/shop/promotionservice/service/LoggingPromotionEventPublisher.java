package com.shop.promotionservice.service;

import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * No-op stand-in for the Task 10 outbox publisher — logs the event the real
 * implementation will relay. Keeps reserve/commit/release wiring testable
 * before the outbox exists; T10 swaps this bean, callers are untouched.
 */
@Component
@Slf4j
public class LoggingPromotionEventPublisher implements PromotionEventPublisher {

    @Override
    public void publishReserved(Campaign campaign, CouponUsageReservation reservation) {
        log.info("promotion.reserved.v1 campaign={} reservation={} order={} (outbox publisher wired in Task 10)",
            campaign.getCode(), reservation.getId(), reservation.getOrderId());
    }
}
