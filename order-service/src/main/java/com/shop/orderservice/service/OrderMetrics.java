package com.shop.orderservice.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer-backed observability surface for order-service (review I4 — brings
 * order-service to parity with ProductMetrics/InventoryMetrics):
 *
 * <ul>
 *   <li>{@code order.outbox.relay.duration} — timer recorded by {@code OrderOutboxRelay}.</li>
 *   <li>{@code order.outbox.pending.count} — gauge updated by the relay each tick.</li>
 *   <li>{@code order.events.published} — counter tagged {@code event_type}
 *       (order.created.v1 / order.updated.v1 / order.cancelled.v1).</li>
 *   <li>{@code order.cache.hit} / {@code order.cache.miss} — gauges backed by
 *       Spring Data Redis {@link CacheStatistics} for the {@code productPrice} cache;
 *       skipped when the CacheManager is not Redis (e.g. {@code spring.cache.type=none}
 *       in tests).</li>
 * </ul>
 */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;
    private final Timer relayDuration;
    private final AtomicInteger pendingOutboxCount = new AtomicInteger(0);

    public OrderMetrics(MeterRegistry registry, CacheManager cacheManager) {
        this.registry = registry;
        this.relayDuration = Timer.builder("order.outbox.relay.duration").register(registry);
        Gauge.builder("order.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);

        if (cacheManager.getCache("productPrice") instanceof RedisCache redisCache) {
            // Bind the RedisCache itself and read getStatistics() INSIDE the gauge
            // value function: getStatistics() returns an immutable snapshot, so a
            // constructor-time capture would freeze the counters at their boot
            // values (re-review finding 2b).
            Gauge.builder("order.cache.hit", redisCache, rc -> rc.getStatistics().getHits())
                .tag("cache", "productPrice")
                .register(registry);
            Gauge.builder("order.cache.miss", redisCache, rc -> rc.getStatistics().getMisses())
                .tag("cache", "productPrice")
                .register(registry);
        }
    }

    public void recordEventPublished(String eventType) {
        registry.counter("order.events.published", "event_type", eventType).increment();
    }

    public void recordRelayDuration(Duration d) { relayDuration.record(d); }

    public void setPendingOutboxCount(int n) { pendingOutboxCount.set(n); }
}
