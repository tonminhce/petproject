package com.shop.orderservice.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Confirm-flow metrics wrapper — hardening §8.
 * Names/tags are contractual (dashboards alert on them):
 * order.confirm.duration{phase}, order.confirm.attempts, order.commit.stuck, order.reconciliation.mixed.
 */
@Component
@RequiredArgsConstructor
public class OrderConfirmMetrics {

    private final MeterRegistry registry;

    public Timer timer(String phase) {
        return Timer.builder("order.confirm.duration").tag("phase", phase)
            .register(registry);
    }

    public void attempt() {
        registry.counter("order.confirm.attempts").increment();
    }

    /** Call once from scheduler config (task 12) — registers the supplier, re-read per scrape. */
    public Gauge stuckGauge(Supplier<Number> value) {
        return Gauge.builder("order.commit.stuck", value).register(registry);
    }

    public void reconciliationMixed() {
        registry.counter("order.reconciliation.mixed").increment();
    }
}
