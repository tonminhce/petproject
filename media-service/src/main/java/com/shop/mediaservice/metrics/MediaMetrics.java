package com.shop.mediaservice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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

    public void recordOutboxDead(int count) {
        registry.counter("media_outbox_dead_total").increment(count);
    }
}
