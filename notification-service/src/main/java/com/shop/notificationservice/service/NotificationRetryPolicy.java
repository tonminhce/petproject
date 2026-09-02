package com.shop.notificationservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * C17 — bounded retry arithmetic for the delivery state machine: exponential
 * backoff ({@code base × 5^(attempt-1)}, the fleet payment/shipping
 * convention) and the hard attempt cap. {@code max-attempts} and the backoff
 * base are configurable via {@code shop.notification.retry.*}; the cap is
 * what turns the retry queue into "bounded attempts → FAILED_PERMANENT".
 */
@Component
public class NotificationRetryPolicy {

    private static final int BACKOFF_MULTIPLIER = 5;
    /** Caps the exponent so legacy rows with huge retry_count cannot overflow. */
    private static final int MAX_BACKOFF_EXPONENT = 10;

    private final int maxAttempts;
    private final long baseBackoffSeconds;

    public NotificationRetryPolicy(
            @Value("${shop.notification.retry.max-attempts:6}") int maxAttempts,
            @Value("${shop.notification.retry.backoff-base-seconds:300}") long baseBackoffSeconds) {
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
    }

    /** True once {@code retryCount} consumed the whole attempt budget. */
    public boolean isExhausted(int retryCount) {
        return retryCount >= maxAttempts;
    }

    public Instant nextRetryAt(int attempt, Instant now) {
        return now.plusSeconds(backoffSeconds(attempt));
    }

    long backoffSeconds(int attempt) {
        long mult = 1;
        for (int i = 1; i < attempt && i <= MAX_BACKOFF_EXPONENT; i++) {
            mult *= BACKOFF_MULTIPLIER;
        }
        return baseBackoffSeconds * mult;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
