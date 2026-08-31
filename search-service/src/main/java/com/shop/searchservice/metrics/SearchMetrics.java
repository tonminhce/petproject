package com.shop.searchservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer-backed observability surface for search-service (fleet
 * RatingMetrics/OrderMetrics pattern):
 *
 * <ul>
 *   <li>{@code search_queries_total{sort}} — counter incremented once per
 *       executed query, tagged with the RESOLVED sort (spec D6 / F3: an
 *       empty-q browse without an explicit sort is tagged {@code newest},
 *       never {@code relevance}).</li>
 * </ul>
 */
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
