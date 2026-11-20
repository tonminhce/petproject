package com.shop.gateway.ratelimit;

public record FleetRateLimitDecision(Outcome outcome, long remaining) {

    public enum Outcome {
        ALLOWED, LIMITED, OUTAGE
    }

    public static FleetRateLimitDecision allowed(final long remaining) {
        return new FleetRateLimitDecision(Outcome.ALLOWED, remaining);
    }

    public static FleetRateLimitDecision rejected() {
        return new FleetRateLimitDecision(Outcome.LIMITED, 0);
    }

    public static FleetRateLimitDecision outage() {
        return new FleetRateLimitDecision(Outcome.OUTAGE, 0);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    public boolean isOutage() {
        return outcome == Outcome.OUTAGE;
    }
}
