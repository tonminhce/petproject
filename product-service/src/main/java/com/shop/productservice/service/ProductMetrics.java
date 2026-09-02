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

/**
 * Micrometer-backed observability surface for product-service.
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code product.cache.hit} / {@code product.cache.miss} — gauges backed
 *       by Spring's {@link CacheStatistics} (one gauge per cache, tagged
 *       {@code cache=product|productBySlug}). Counters-as-gauges (rather than
 *       per-call increments) avoid the AOP dance around Spring's
 *       {@code CacheInterceptor} and stay accurate under load.</li>
 *   <li>{@code product.events.published} — counter tagged with {@code event_type};
 *       incremented from the {@code Transactional*EventPublisher.save()} methods.
 *       The name is service-scoped, not aggregate-scoped: category/brand
 *       lifecycle events flow through the same counter with their own
 *       {@code event_type} tag ({@code CategoryCreated}, {@code BrandDeleted}, …).</li>
 *   <li>{@code product.outbox.relay.duration} — timer; recorded from
 *       {@code OutboxRelay} in a {@code finally} block.</li>
 *   <li>{@code product.outbox.pending.count} — gauge backed by an
 *       {@link AtomicInteger} updated by {@code OutboxRelay}.</li>
 * </ul>
 */
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

        // Cache hit/miss: bind to Spring Data Redis's CacheStatistics so we get
        // accurate counts from the same source the CacheInterceptor records into.
        // The Spring `Cache` interface does not expose getStatistics(); only
        // `RedisCache` does — cast and skip if the cast fails (e.g. test context
        // using a different CacheManager implementation).
        //
        // Bind the RedisCache itself and call getStatistics() INSIDE the gauge
        // function: it returns an immutable snapshot, so a constructor-time capture
        // would freeze the counters at their boot values (platform metrics fix).
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

    // --- H-3 media reconciliation sweep ---

    /**
     * H-3 sweep observability: one increment per product row whose media
     * reference was HEAD-checked this cycle (kept or cleared alike).
     */
    public void recordSweepChecked() {
        registry.counter("product_media_sweep_checked_total").increment();
    }

    /**
     * H-3 sweep observability: incremented by the number of media references
     * actually removed. A dangling mediaId shared by several rows repairs in
     * one {@code clearReference} call — the caller passes the affected-row
     * count so the meter tracks cleared ROWS, not repair calls.
     */
    public void recordSweepCleared(long cleared) {
        registry.counter("product_media_sweep_cleared_total").increment(cleared);
    }
}
