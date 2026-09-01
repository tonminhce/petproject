package com.shop.mediaservice.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MediaMetrics metrics = new MediaMetrics(registry);

    @Test
    void recordUpload_created_incrementsCounterWithOutcomeTag() {
        metrics.recordUpload(MediaMetrics.OUTCOME_CREATED);
        metrics.recordUpload(MediaMetrics.OUTCOME_CREATED);

        assertThat(registry.get("media_uploads_total").tag("outcome", "created").counter().count())
                .isEqualTo(2.0d);
    }

    @Test
    void recordUpload_duplicate_incrementsCounterWithOutcomeTag() {
        metrics.recordUpload(MediaMetrics.OUTCOME_DUPLICATE);

        assertThat(registry.get("media_uploads_total").tag("outcome", "duplicate").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void recordUpload_rejected_incrementsCounterWithOutcomeTag() {
        metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);

        assertThat(registry.get("media_uploads_total").tag("outcome", "rejected").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void recordPresign_incrementsCounterTaggedWithVariant() {
        metrics.recordPresign("display");
        metrics.recordPresign("display");
        metrics.recordPresign("thumb");

        assertThat(registry.get("media_presigned_total").tag("variant", "display").counter().count())
                .isEqualTo(2.0d);
        assertThat(registry.get("media_presigned_total").tag("variant", "thumb").counter().count())
                .isEqualTo(1.0d);
    }
}
