package com.shop.mediaservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer-backed observability surface for media-service (fleet
 * SearchMetrics/RatingMetrics pattern):
 *
 * <ul>
 *   <li>{@code media_uploads_total{outcome}} — counter incremented once per
 *       processed upload, tagged {@code created} (new media persisted),
 *       {@code duplicate} (SHA-256 dedup hit — D1 200 + duplicate:true) or
 *       {@code rejected} (any 400/413/415/503 pipeline rejection, spec D6).</li>
 * </ul>
 */
@Component
public class MediaMetrics {

    public static final String OUTCOME_CREATED = "created";
    public static final String OUTCOME_DUPLICATE = "duplicate";
    public static final String OUTCOME_REJECTED = "rejected";

    private final MeterRegistry registry;

    public MediaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordUpload(String outcome) {
        registry.counter("media_uploads_total", "outcome", outcome).increment();
    }
}
