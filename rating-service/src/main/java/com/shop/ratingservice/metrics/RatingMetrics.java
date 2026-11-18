package com.shop.ratingservice.metrics;

import com.shop.ratingservice.constant.RatingAction;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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
