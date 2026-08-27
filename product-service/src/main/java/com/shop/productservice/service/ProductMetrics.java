package com.shop.productservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer-backed observability surface for product-service.
 *
 * <p>Five meters:
 * <ul>
 *   <li>{@code product.cache.hit} / {@code product.cache.miss} — counters
 *       (wired by future cache aspect or manual instrumentation).</li>
 *   <li>{@code product.events.published} — counter tagged with {@code event_type};
 *       incremented from {@code TransactionalProductEventPublisher.save()}.</li>
 *   <li>{@code product.outbox.relay.duration} — timer; recorded from
 *       {@code OutboxRelay} (wiring deferred to follow-up task).</li>
 *   <li>{@code product.outbox.pending.count} — gauge backed by an
 *       {@link AtomicInteger} updated by {@code OutboxRelay}
 *       (wiring deferred to follow-up task).</li>
 * </ul>
 */
@Component
public class ProductMetrics {

    private final MeterRegistry registry;
    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Timer relayDuration;
    private final AtomicInteger pendingOutboxCount = new AtomicInteger(0);

    public ProductMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cacheHit = Counter.builder("product.cache.hit").register(registry);
        this.cacheMiss = Counter.builder("product.cache.miss").register(registry);
        this.relayDuration = Timer.builder("product.outbox.relay.duration").register(registry);
        Gauge.builder("product.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);
    }

    public void recordCacheHit()  { cacheHit.increment(); }
    public void recordCacheMiss() { cacheMiss.increment(); }

    public void recordEventPublished(String eventType) {
        registry.counter("product.events.published", "event_type", eventType).increment();
    }

    public void recordRelayDuration(Duration d) { relayDuration.record(d); }

    public void setPendingOutboxCount(int n) { pendingOutboxCount.set(n); }
}
