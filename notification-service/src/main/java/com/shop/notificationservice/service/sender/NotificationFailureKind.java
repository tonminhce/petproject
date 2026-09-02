package com.shop.notificationservice.service.sender;

/**
 * C17 — classification of a delivery failure: whether the retry scheduler
 * should get another shot at it, or whether retrying is pointless.
 */
public enum NotificationFailureKind {
    /** IO/timeout/SMTP 4xx-5xx/auth hiccups — the scheduler will retry. */
    TRANSIENT,
    /** Invalid/unknown recipient, rejected message — retrying is futile. */
    PERMANENT
}
