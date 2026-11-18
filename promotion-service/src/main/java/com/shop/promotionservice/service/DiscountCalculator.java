package com.shop.promotionservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DiscountCalculator {

    public static final String TYPE_PERCENT = "PERCENT";
    public static final String TYPE_FIXED = "FIXED";

    private static final int MONEY_SCALE = 2;

    private DiscountCalculator() {
    }

    public static BigDecimal compute(String discountType, BigDecimal value, BigDecimal orderAmount) {
        if (TYPE_PERCENT.equals(discountType)) {
            return orderAmount.multiply(value)
                .divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (TYPE_FIXED.equals(discountType)) {
            return value.min(orderAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        throw new IllegalStateException("Unknown discount type: " + discountType);
    }
}
