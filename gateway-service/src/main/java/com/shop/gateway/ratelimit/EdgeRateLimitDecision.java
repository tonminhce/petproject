package com.shop.gateway.ratelimit;

public record EdgeRateLimitDecision(boolean allowed, long remaining) {

    public static EdgeRateLimitDecision allowed(final long remaining) {
        return new EdgeRateLimitDecision(true, remaining);
    }

    public static EdgeRateLimitDecision rejected() {
        return new EdgeRateLimitDecision(false, 0);
    }
}
