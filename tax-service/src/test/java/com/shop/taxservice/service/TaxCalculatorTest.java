package com.shop.taxservice.service;

import com.shop.taxservice.dto.response.TaxCalculateResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TaxCalculatorTest {

    @Test
    void standardAmountAndRate() {
        TaxCalculateResponse response = TaxCalculator.calculate(new BigDecimal("100.00"), new BigDecimal("10.00"));

        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("10.00"));
        assertThat(response.appliedRate()).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void zeroAmountYieldsZeroTax() {
        TaxCalculateResponse response = TaxCalculator.calculate(new BigDecimal("0"), new BigDecimal("10"));

        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void exactHalfCentTieRoundsUp() {
        TaxCalculateResponse response = TaxCalculator.calculate(new BigDecimal("0.05"), new BigDecimal("50"));

        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("0.03"));
    }

    @Test
    void exactNoTieRoundsDown() {
        TaxCalculateResponse response = TaxCalculator.calculate(new BigDecimal("0.04"), new BigDecimal("50"));

        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("0.02"));
    }

    @Test
    void resultHasTwoDecimalScale() {
        TaxCalculateResponse response = TaxCalculator.calculate(new BigDecimal("1"), new BigDecimal("7"));

        assertThat(response.taxAmount()).isEqualTo(new BigDecimal("0.07"));
    }
}
