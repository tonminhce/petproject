package com.shop.promotionservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer-backed observability surface for promotion-service — mirrors
 * inventory-service's {@code InventoryMetrics} so outbox health is visible
 * the same way across the event-producing services (no Redis cache gauges:
 * promotion-service has no cache tier).
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code promotion.events.published} — counter tagged with
 *       {@code event_type}; incremented from
 *       {@code TransactionalPromotionEventPublisher.save()}.</li>
 *   <li>{@code promotion.outbox.relay.duration} — timer; recorded from
 *       {@code PromotionOutboxRelay} in a {@code finally} block.</li>
 *   <li>{@code promotion.outbox.pending.count} — gauge backed by an
 *       {@link AtomicInteger} updated by {@code PromotionOutboxRelay}.</li>
 * </ul>
 */
@Component
public class PromotionMetrics {

    private final MeterRegistry registry;
    private final Timer relayDuration;
    private final AtomicInteger pendingOutboxCount = new AtomicInteger(0);

    public PromotionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.relayDuration = Timer.builder("promotion.outbox.relay.duration").register(registry);
        Gauge.builder("promotion.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);
    }

    public void recordEventPublished(String eventType) {
        registry.counter("promotion.events.published", "event_type", eventType).increment();
    }

    public void recordRelayDuration(Duration d) { relayDuration.record(d); }

    public void setPendingOutboxCount(int n) { pendingOutboxCount.set(n); }
}
