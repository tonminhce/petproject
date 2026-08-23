package com.shop.common.core.util;

import java.util.UUID;

/**
 * String helpers used across the platform. Kept dependency-free so they are
 * safe to call from non-Spring code paths.
 */
public final class StringUtils {

    private StringUtils() {
    }

    public static boolean isBlank(CharSequence value) {
        if (value == null) {
            return true;
        }
        int len = value.length();
        for (int i = 0; i < len; i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNotBlank(CharSequence value) {
        return !isBlank(value);
    }

    public static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
