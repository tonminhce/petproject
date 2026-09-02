package com.shop.notificationservice.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C17 — bounded retry policy: exponential backoff (5× per attempt, fleet
 * payment/shipping convention) with a hard cap on attempts. The cap is what
 * turns "retry forever" into "FAILed_PERMANENT after N attempts".
 */
class NotificationRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    private final NotificationRetryPolicy policy = new NotificationRetryPolicy(6, 300);

    @Test
    void backoffGrowsExponentiallyWithFleetFiveXBase() {
        assertThat(policy.backoffSeconds(1)).isEqualTo(300);
        assertThat(policy.backoffSeconds(2)).isEqualTo(1500);
        assertThat(policy.backoffSeconds(3)).isEqualTo(7500);
    }

    @Test
    void nextRetryAt_addsBackoffToNow() {
        assertThat(policy.nextRetryAt(2, NOW)).isEqualTo(NOW.plusSeconds(1500));
    }

    @Test
    void attemptIsNotExhaustedBelowTheCap() {
        assertThat(policy.isExhausted(0)).isFalse();
        assertThat(policy.isExhausted(5)).isFalse();
    }

    @Test
    void attemptAtOrBeyondTheCapIsExhausted() {
        assertThat(policy.isExhausted(6)).isTrue();
        assertThat(policy.isExhausted(7)).isTrue();
    }

    @Test
    void hugeLegacyAttemptCount_doesNotOverflow() {
        // Legacy/backfilled rows can carry arbitrary retry_count values; the
        // backoff multiplication must stay a sane positive number.
        long backoff = policy.backoffSeconds(50);

        assertThat(backoff).isPositive();
        assertThat(backoff).isLessThan(Long.MAX_VALUE);
    }
}
