package com.shop.common.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MoneyUtils {

    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of("VND", "JPY", "KRW");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MoneyUtils() {
    }

    public static int getScaleForCurrency(String currency) {
        if (currency != null && ZERO_DECIMAL_CURRENCIES.contains(currency.toUpperCase(Locale.ROOT))) {
            return 0;
        }
        return 2;
    }

    public static BigDecimal round(BigDecimal amount, String currency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        int scale = getScaleForCurrency(currency);
        return amount.setScale(scale, RoundingMode.HALF_UP);
    }

    public static String format(BigDecimal amount, String currency) {
        if (amount == null) {
            return "0";
        }
        String curr = currency != null ? currency.toUpperCase(Locale.ROOT) : "VND";
        int scale = getScaleForCurrency(curr);
        BigDecimal rounded = amount.setScale(scale, RoundingMode.HALF_UP);

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');

        String pattern = scale == 0 ? "#,##0" : "#,##0.00";
        DecimalFormat df = new DecimalFormat(pattern, symbols);
        String formatted = df.format(rounded);

        if ("VND".equals(curr)) {
            return formatted + " \u20ab";
        } else if ("USD".equals(curr)) {
            return "$" + formatted;
        } else if ("EUR".equals(curr)) {
            return "\u20ac" + formatted;
        }
        return formatted + " " + curr;
    }

    public static BigDecimal calculateDiscount(BigDecimal amount, BigDecimal percentage) {
        if (amount == null || percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(percentage).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateTax(BigDecimal subtotal, BigDecimal taxRate) {
        if (subtotal == null || taxRate == null || taxRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return subtotal.multiply(taxRate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
