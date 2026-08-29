package com.shop.orderservice.service;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderConfirmMetrics unit tests — hardening §8 metric names/tags.
 * Wrapper over MeterRegistry: phase timers, attempts counter, stuck gauge, reconciliation-mixed counter.
 */
class OrderConfirmMetricsTest {

    private SimpleMeterRegistry registry;
    private OrderConfirmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrderConfirmMetrics(registry);
    }

    /** §8: order.confirm.duration{phase=commit_promotion|commit_inventory|publish|rollback}. */
    @Test
    void timer_registersPhaseTimerWithExactNameAndTag_andDedupesPerPhase() {
        Timer first = metrics.timer("commit_promotion");

        assertThat(registry.get("order.confirm.duration").tag("phase", "commit_promotion").timer())
            .isSameAs(first);
        assertThat(metrics.timer("commit_promotion")).isSameAs(first);          // dedupe
        assertThat(metrics.timer("commit_inventory")).isNotSameAs(first);       // distinct phase → distinct timer
    }

    /** §8: order.confirm.attempts counter. */
    @Test
    void attempt_incrementsAttemptsCounter() {
        metrics.attempt();
        metrics.attempt();

        assertThat(registry.counter("order.confirm.attempts").count()).isEqualTo(2.0);
    }

    /** §8: order.commit.stuck gauge — supplier-backed, re-read on each scrape. */
    @Test
    void stuckGauge_registersGaugeBackedBySupplier() {
        AtomicLong stuck = new AtomicLong(3);

        metrics.stuckGauge(stuck::get);

        assertThat(registry.get("order.commit.stuck").gauge().value()).isEqualTo(3.0);
        stuck.set(7);
        assertThat(registry.get("order.commit.stuck").gauge().value()).isEqualTo(7.0);
    }

    /** §8: order.reconciliation.mixed counter. */
    @Test
    void reconciliationMixed_incrementsCounter() {
        metrics.reconciliationMixed();

        assertThat(registry.counter("order.reconciliation.mixed").count()).isEqualTo(1.0);
    }
}
