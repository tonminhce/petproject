package com.shop.gateway.ratelimit;

public record EdgeRateLimitDecision(Outcome outcome, long remaining) {

    public enum Outcome {
        ALLOWED, LIMITED, OUTAGE
    }

    public static EdgeRateLimitDecision allowed(final long remaining) {
        return new EdgeRateLimitDecision(Outcome.ALLOWED, remaining);
    }

    public static EdgeRateLimitDecision rejected() {
        return new EdgeRateLimitDecision(Outcome.LIMITED, 0);
    }

    public static EdgeRateLimitDecision outage() {
        return new EdgeRateLimitDecision(Outcome.OUTAGE, 0);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    public boolean limited() {
        return outcome == Outcome.LIMITED;
    }

    public boolean isOutage() {
        return outcome == Outcome.OUTAGE;
    }
}
