package com.shop.promotionservice.dto.request;

import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.validation.ValidDiscountValue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

@ValidDiscountValue
public record CampaignRequest(
    @NotBlank @Size(max = 50) String code,
    @NotBlank String name,
    @NotBlank @Pattern(regexp = "PERCENT|FIXED") String discountType,
    @NotNull @DecimalMin("0") BigDecimal discountValue,
    @PositiveOrZero BigDecimal minOrderAmount,
    Instant startsAt,
    Instant endsAt,
    @Positive Integer maxRedemptions,
    @Positive BigDecimal totalBudget,
    @PositiveOrZero Integer perUserLimit,
    CampaignStatus status
) {}
