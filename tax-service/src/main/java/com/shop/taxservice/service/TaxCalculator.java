package com.shop.taxservice.service;

import com.shop.taxservice.dto.response.TaxCalculateResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TaxCalculator {

    private TaxCalculator() {
    }

    public static TaxCalculateResponse calculate(BigDecimal amount, BigDecimal ratePct) {
        BigDecimal taxAmount = amount.multiply(ratePct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new TaxCalculateResponse(taxAmount, ratePct);
    }
}
