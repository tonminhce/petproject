package com.shop.promotionservice.dto.response;

import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID reservationId,
    UUID campaignId,
    String code,
    BigDecimal discountAmount,
    BigDecimal finalAmount,
    String status,
    Instant expiresAt
) {

    public static ReservationResponse from(Campaign c, CouponUsageReservation r) {
        return new ReservationResponse(
            r.getId(),
            r.getCampaignId(),
            c.getCode(),
            r.getDiscountAmount(),
            r.getOrderAmount().subtract(r.getDiscountAmount()),
            r.getStatus().name(),
            r.getExpiresAt()
        );
    }
}
