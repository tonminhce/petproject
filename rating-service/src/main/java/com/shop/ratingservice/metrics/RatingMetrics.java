package com.shop.ratingservice.metrics;

import com.shop.ratingservice.constant.RatingAction;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer-backed observability surface for rating-service (spec D7 —
 * fleet OrderMetrics/ShippingMetrics pattern):
 *
 * <ul>
 *   <li>{@code rating_submitted_total{action}} — counter incremented once per
 *       rating lifecycle write (CREATED / UPDATED / HIDDEN / UNHIDDEN), from
 *       the single choke point {@code RatingEventService.record()}.</li>
 * </ul>
 */
@Component
public class RatingMetrics {

    private final MeterRegistry registry;

    public RatingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSubmitted(RatingAction action) {
        registry.counter("rating_submitted_total", "action", action.name()).increment();
    }
}
