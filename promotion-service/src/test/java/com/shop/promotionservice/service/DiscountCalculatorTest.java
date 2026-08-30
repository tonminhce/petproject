package com.shop.promotionservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscountCalculatorTest {

    @Test
    @DisplayName("PERCENT 199.99 x 10% = 20.00 (19.999 rounded HALF_UP)")
    void percentTenOfOneNinetyNine() {
        BigDecimal result = DiscountCalculator.compute(
            "PERCENT", new BigDecimal("10.00"), new BigDecimal("199.99"));

        assertEquals(new BigDecimal("20.00"), result);
    }

    @Test
    @DisplayName("PERCENT 100 x 33.33% = 33.33")
    void percentThirtyThreeOfHundred() {
        BigDecimal result = DiscountCalculator.compute(
            "PERCENT", new BigDecimal("33.33"), new BigDecimal("100.00"));

        assertEquals(new BigDecimal("33.33"), result);
    }

    @Test
    @DisplayName("PERCENT HALF_UP tie: 3.00 x 67.50% = 2.025 -> 2.03 (HALF_EVEN would yield 2.02)")
    void percentHalfUpTieBreak() {
        BigDecimal result = DiscountCalculator.compute(
            "PERCENT", new BigDecimal("67.50"), new BigDecimal("3.00"));

        assertEquals(new BigDecimal("2.03"), result);
    }

    @Test
    @DisplayName("FIXED 50000 capped at order amount 199.99")
    void fixedCappedAtOrderAmount() {
        BigDecimal result = DiscountCalculator.compute(
            "FIXED", new BigDecimal("50000"), new BigDecimal("199.99"));

        assertEquals(new BigDecimal("199.99"), result);
    }

    @Test
    @DisplayName("FIXED 50 of 199.99 = 50.00")
    void fixedUnderOrderAmount() {
        BigDecimal result = DiscountCalculator.compute(
            "FIXED", new BigDecimal("50"), new BigDecimal("199.99"));

        assertEquals(new BigDecimal("50.00"), result);
    }

    @Test
    @DisplayName("Unknown discount type -> IllegalStateException")
    void unknownTypeRejected() {
        assertThrows(IllegalStateException.class, () -> DiscountCalculator.compute(
            "TWO_FOR_ONE", new BigDecimal("10"), new BigDecimal("199.99")));
    }
}
