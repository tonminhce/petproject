package com.shop.gateway.filter;

import java.util.Map;

public final class RateLimitHeaders {

    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RETRY_AFTER = "Retry-After";

    private RateLimitHeaders() {
    }

    public static String remainingValue(final Map<String, String> limiterHeaders) {
        final String remaining = limiterHeaders == null ? null : limiterHeaders.get(REMAINING);
        if (remaining == null) {
            return "0";
        }
        try {
            return Long.toString(Math.max(0, Long.parseLong(remaining)));
        } catch (final NumberFormatException e) {
            return "0";
        }
    }
}
