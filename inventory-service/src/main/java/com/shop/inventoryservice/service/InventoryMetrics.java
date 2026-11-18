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
