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
 *       {@code rejected} (any 400/413/415/503 pipeline rejection, spec D6);</li>
 *   <li>{@code media_presigned_total{variant}} — counter incremented once per
 *       successfully resolved presigned GET (spec D6), tagged with the variant
 *       actually served: {@code original}, {@code display} or {@code thumb}.</li>
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

    public void recordPresign(String variant) {
        registry.counter("media_presigned_total", "variant", variant).increment();
    }

    /**
     * H-5 relay aging: incremented by the number of FAILED outbox rows the
     * nightly retention scheduler aged to terminal DEAD — the ops signal that
     * a publish has been broken past the retention window and needs manual
     * root-cause (DEAD rows are never auto-purged or replayed).
     */
    public void recordOutboxDead(int count) {
        registry.counter("media_outbox_dead_total").increment(count);
    }
}
