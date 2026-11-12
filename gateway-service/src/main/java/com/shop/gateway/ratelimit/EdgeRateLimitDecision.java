package com.shop.gateway.ratelimit;

/**
 * The decision of one edge rate-limit attempt.
 *
 * @param allowed   whether the request may pass
 * @param remaining the tokens left in the bucket after the attempt
 */
public record EdgeRateLimitDecision(boolean allowed, long remaining) {

    public static EdgeRateLimitDecision allowed(final long remaining) {
        return new EdgeRateLimitDecision(true, remaining);
    }

    public static EdgeRateLimitDecision rejected() {
        return new EdgeRateLimitDecision(false, 0);
    }
}
