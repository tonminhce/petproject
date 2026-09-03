package com.shop.common.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyUtilsTest {

    @Test
    void round_vndRoundsToZeroDecimals() {
        BigDecimal amount = new BigDecimal("12345.67");
        BigDecimal result = MoneyUtils.round(amount, "VND");
        assertThat(result).isEqualByComparingTo("12346");
    }

    @Test
    void round_usdRoundsToTwoDecimals() {
        BigDecimal amount = new BigDecimal("12.3456");
        BigDecimal result = MoneyUtils.round(amount, "USD");
        assertThat(result).isEqualByComparingTo("12.35");
    }

    @Test
    void format_vndAppendsCurrencySymbol() {
        BigDecimal amount = new BigDecimal("500000");
        String formatted = MoneyUtils.format(amount, "VND");
        assertThat(formatted).isEqualTo("500,000 \u20ab");
    }

    @Test
    void format_usdPrependsDollar() {
        BigDecimal amount = new BigDecimal("1250.5");
        String formatted = MoneyUtils.format(amount, "USD");
        assertThat(formatted).isEqualTo("$1,250.50");
    }

    @Test
    void calculateDiscount_correctPercentage() {
        BigDecimal amount = new BigDecimal("200.00");
        BigDecimal discount = MoneyUtils.calculateDiscount(amount, new BigDecimal("15"));
        assertThat(discount).isEqualByComparingTo("30.00");
    }

    @Test
    void calculateTax_correctRate() {
        BigDecimal subtotal = new BigDecimal("100.00");
        BigDecimal tax = MoneyUtils.calculateTax(subtotal, new BigDecimal("8.5"));
        assertThat(tax).isEqualByComparingTo("8.50");
    }
}
