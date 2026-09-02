package com.shop.notificationservice.constant;

/**
 * C12/C17 delivery state machine. A row is born {@link #PENDING} (never
 * SENT-from-birth) and reaches {@link #SENT} only after the sender's provider
 * ack. Transitions are owned exclusively by {@code NotificationWriter};
 * retries are bounded: {@code FAILED_RETRYABLE → (scheduler) → SENT |
 * FAILED_PERMANENT}.
 */
public enum NotificationStatus {

    /** Persisted, waiting for its first delivery attempt. */
    PENDING,

    /** Claimed by a delivery attempt (Kafka consumer or retry scheduler). */
    SENDING,

    /** Provider acked the send — the only state a user should rely on. */
    SENT,

    /** Unknown event type — deliberately not delivered. */
    SKIPPED,

    /** Transient failure; the retry scheduler will re-claim after backoff. */
    FAILED_RETRYABLE,

    /** Terminal: permanent failure or retry budget exhausted. */
    FAILED_PERMANENT,

    /**
     * Legacy read-compat value written only by pre-C12 instances during a
     * rolling deploy. Never written by current code; the retry scheduler
     * reclaims legacy rows so they are not silently lost.
     */
    FAILED
}
