package com.shop.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code shop.product.media-sweep.*} — the H-3 reconciliation sweep that
 * durably closes the at-most-once window of the {@code MediaDeleted} consumer:
 * periodically re-verify live products' media references against media-service
 * and clear the dangling ones the event path missed (bounded, fail-safe).
 *
 * @param enabled gate — on by default (prod semantics); flip off to suspend
 *                the sweep without undeploying the scheduler
 * @param cron    Spring cron (6 fields); default every 30 minutes
 * @param limit   max product rows examined per cycle — bounds the media-service
 *                HEAD traffic and keeps cycles short (outbox-retention style)
 */
@ConfigurationProperties(prefix = "shop.product.media-sweep")
public record ProductMediaSweepProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 */30 * * * *") String cron,
        @DefaultValue("100") int limit
) {
    public ProductMediaSweepProperties {
        if (limit <= 0) {
            throw new IllegalArgumentException("shop.product.media-sweep.limit must be positive, got: " + limit);
        }
    }
}
