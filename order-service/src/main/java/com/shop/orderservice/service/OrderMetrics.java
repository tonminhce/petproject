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
