package com.shop.mediaservice.outbox;

/**
 * D4 — the two lifecycle event types carried on {@code media.lifecycle.v1}.
 * {@link #name()} IS the {@code eventType} written to the outbox row and the
 * payload; consumers ack-skip anything else.
 */
public enum MediaEventType {
    MediaCreated,
    MediaDeleted
}
