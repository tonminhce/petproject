package com.shop.productservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ProductMetrics {

    private final MeterRegistry registry;
    private final Timer relayDuration;
    private final AtomicInteger pendingOutboxCount = new AtomicInteger(0);

    public ProductMetrics(MeterRegistry registry, CacheManager cacheManager) {
        this.registry = registry;
        this.relayDuration = Timer.builder("product.outbox.relay.duration").register(registry);
        Gauge.builder("product.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);

        for (String cacheName : List.of("product", "productBySlug", "category", "brand")) {
            if (!(cacheManager.getCache(cacheName) instanceof RedisCache redisCache)) {
                continue;
            }
            Gauge.builder("product.cache.hit", redisCache, rc -> rc.getStatistics().getHits())
                .tag("cache", cacheName)
                .register(registry);
            Gauge.builder("product.cache.miss", redisCache, rc -> rc.getStatistics().getMisses())
                .tag("cache", cacheName)
                .register(registry);
        }
    }

    public void recordEventPublished(String eventType) {
        registry.counter("product.events.published", "event_type", eventType).increment();
    }

    public void recordRelayDuration(Duration d) { relayDuration.record(d); }

    public void setPendingOutboxCount(int n) { pendingOutboxCount.set(n); }

    public void recordSweepChecked() {
        registry.counter("product_media_sweep_checked_total").increment();
    }

    public void recordSweepCleared(long cleared) {
        registry.counter("product_media_sweep_cleared_total").increment(cleared);
    }
}
