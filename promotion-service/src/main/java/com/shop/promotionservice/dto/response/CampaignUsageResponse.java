package com.shop.promotionservice.dto.response;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CampaignUsageResponse(
    UUID reservationId,
    UUID userId,
    UUID orderId,
    BigDecimal orderAmount,
    BigDecimal discountAmount,
    UsageStatus status,
    Instant reservedAt,
    Instant committedAt,
    Instant releasedAt
) {

    public static CampaignUsageResponse from(CouponUsageReservation r) {
        return new CampaignUsageResponse(
            r.getId(),
            r.getUserId(),
            r.getOrderId(),
            r.getOrderAmount(),
            r.getDiscountAmount(),
            r.getStatus(),
            r.getReservedAt(),
            r.getCommittedAt(),
            r.getReleasedAt()
        );
    }
}
