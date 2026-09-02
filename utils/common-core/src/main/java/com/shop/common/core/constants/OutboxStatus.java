package com.shop.common.core.constants;

/**
 * Outbox row lifecycle. DB-INTERNAL state — never travels on the wire
 * (consumers only see broker payloads), so adding values here is invisible
 * outside the owning service's database.
 *
 * <ul>
 *   <li>{@link #PENDING} — row written, awaiting relay publish</li>
 *   <li>{@link #SENT} — published to the broker; terminal-success, purged by
 *       retention after the window</li>
 *   <li>{@link #FAILED} — publish failed past max-retries; NOT terminal where
 *       the owning relay replays (media keeps replaying FAILED, H-5; other
 *       relays park it for ops)</li>
 *   <li>{@link #DEAD} — terminal failure (H-5 media relay aging): FAILED rows
 *       older than the retention window are parked here + WARN + metric, and
 *       are never replayed — they need manual root-cause before deletion</li>
 * </ul>
 */
public enum OutboxStatus {
    PENDING, SENT, FAILED, DEAD
}
