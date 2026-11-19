package com.shop.orderservice.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMetricsTest {

    @Test
    void nonRedisCacheManager_registersRelayMetricsAndSkipsCacheGauges() {
        var registry = new SimpleMeterRegistry();
        var cacheManager = new ConcurrentMapCacheManager();

        OrderMetrics metrics = new OrderMetrics(registry, cacheManager);

        assertThat(registry.find("order.outbox.relay.duration").timer()).isNotNull();
        assertThat(registry.find("order.outbox.pending.count").gauge()).isNotNull();
        assertThat(registry.find("order.cache.hit").gauge()).isNull();
        assertThat(registry.find("order.cache.miss").gauge()).isNull();

        metrics.recordEventPublished("order.created.v1");
        metrics.recordRelayDuration(Duration.ofMillis(5));
        metrics.setPendingOutboxCount(3);
        assertThat(registry.get("order.events.published").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("order.outbox.pending.count").gauge().value()).isEqualTo(3.0);
    }
}
