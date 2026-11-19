package com.shop.searchservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SearchMetrics {

    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordQuery(String sort) {
        registry.counter("search_queries_total", "sort", sort).increment();
    }
}
