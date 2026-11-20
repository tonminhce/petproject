package com.shop.common.core.constants;

import java.util.Locale;
import java.util.Set;

public final class PlatformCurrency {

    public static final String DEFAULT = "VND";
    public static final int CODE_LENGTH = 3;
    public static final Set<String> SUPPORTED = Set.of("VND", "USD");

    private PlatformCurrency() {
    }

    public static boolean isSupported(String currency) {
        return currency != null && SUPPORTED.contains(currency.toUpperCase(Locale.ROOT));
    }

    public static String normalize(String currency) {
        return currency == null ? null : currency.toUpperCase(Locale.ROOT);
    }
}
