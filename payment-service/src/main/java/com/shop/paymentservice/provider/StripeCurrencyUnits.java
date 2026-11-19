package com.shop.paymentservice.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

public final class StripeCurrencyUnits {

    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF");

    private StripeCurrencyUnits() {
    }

    /** Major-unit BigDecimal (payment row) → Stripe minor-unit long. */
    public static long toMinor(BigDecimal amount, String currency) {
        int digits = minorUnitDigits(currency);
        return amount.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Stripe minor-unit long (webhook payload) → major-unit BigDecimal. */
    public static BigDecimal fromMinor(Long minor, String currency) {
        if (minor == null) {
            return null;
        }
        return BigDecimal.valueOf(minor, minorUnitDigits(currency));
    }

    private static int minorUnitDigits(String currency) {
        return ZERO_DECIMAL_CURRENCIES.contains(
                currency == null ? "" : currency.toUpperCase(Locale.ROOT)) ? 0 : 2;
    }
}
