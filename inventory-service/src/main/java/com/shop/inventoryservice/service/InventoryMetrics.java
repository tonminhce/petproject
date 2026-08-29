package com.shop.inventoryservice.service;

import io.micrometer.core.instrument.Counter;
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
 * Micrometer-backed observability surface for inventory-service — mirrors
 * product-service's {@code ProductMetrics} so outbox health is visible the
 * same way across the two event-producing services.
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code inventory.cache.hit} / {@code inventory.cache.miss} — gauges
 *       backed by Spring's {@link CacheStatistics} for the {@code inventory}
 *       cache. Counters-as-gauges (rather than per-call increments) avoid the
 *       AOP dance around Spring's {@code CacheInterceptor}.</li>
 *   <li>{@code inventory.events.published} — counter tagged with
 *       {@code event_type}; incremented from
 *       {@code TransactionalInventoryEventPublisher.save()}.</li>
 *   <li>{@code inventory.outbox.relay.duration} — timer; recorded from
 *       {@code InventoryOutboxRelay} in a {@code finally} block.</li>
 *   <li>{@code inventory.outbox.pending.count} — gauge backed by an
 *       {@link AtomicInteger} updated by {@code InventoryOutboxRelay}.</li>
 * </ul>
 */
@Component
public class InventoryMetrics {

    private final MeterRegistry registry;
    private final Timer relayDuration;
    private final AtomicInteger pendingOutboxCount = new AtomicInteger(0);

    public InventoryMetrics(MeterRegistry registry, CacheManager cacheManager) {
        this.registry = registry;
        this.relayDuration = Timer.builder("inventory.outbox.relay.duration").register(registry);
        Gauge.builder("inventory.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);

        // Cache hit/miss: bind to Spring Data Redis's CacheStatistics. The Spring
        // `Cache` interface does not expose getStatistics(); only `RedisCache`
        // does — cast and skip if the cast fails (e.g. test context running with
        // spring.cache.type=none).
        //
        // Bind the RedisCache itself and call getStatistics() INSIDE the gauge
        // function: it returns an immutable snapshot, so a constructor-time capture
        // would freeze the counters at their boot values (platform metrics fix).
        if (cacheManager.getCache("inventory") instanceof RedisCache redisCache) {
            Gauge.builder("inventory.cache.hit", redisCache, rc -> rc.getStatistics().getHits())
                .tag("cache", "inventory")
                .register(registry);
            Gauge.builder("inventory.cache.miss", redisCache, rc -> rc.getStatistics().getMisses())
                .tag("cache", "inventory")
                .register(registry);
        }
    }

    public void recordEventPublished(String eventType) {
        registry.counter("inventory.events.published", "event_type", eventType).increment();
    }

    public void recordRelayDuration(Duration d) { relayDuration.record(d); }

    public void setPendingOutboxCount(int n) { pendingOutboxCount.set(n); }
}
