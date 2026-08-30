package com.shop.promotionservice.dto.response;

import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.entity.Campaign;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CampaignResponse(
    UUID id,
    String code,
    String name,
    String discountType,
    BigDecimal discountValue,
    BigDecimal minOrderAmount,
    Instant startsAt,
    Instant endsAt,
    Integer maxRedemptions,
    BigDecimal totalBudget,
    Integer perUserLimit,
    CampaignStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static CampaignResponse from(Campaign c) {
        return new CampaignResponse(
            c.getId(),
            c.getCode(),
            c.getName(),
            c.getDiscountType(),
            c.getDiscountValue(),
            c.getMinOrderAmount(),
            c.getStartsAt(),
            c.getEndsAt(),
            c.getMaxRedemptions(),
            c.getTotalBudget(),
            c.getPerUserLimit(),
            c.getStatus(),
            c.getCreatedAt(),
            c.getUpdatedAt()
        );
    }
}
