package com.shop.ratingservice.metrics;

import com.shop.ratingservice.constant.RatingAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RatingMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RatingMetrics metrics = new RatingMetrics(registry);

    @Test
    void recordSubmitted_created_incrementsCounterWithActionTag() {
        metrics.recordSubmitted(RatingAction.CREATED);
        metrics.recordSubmitted(RatingAction.CREATED);

        assertThat(registry.get("rating_submitted_total").tag("action", "CREATED").counter().count())
                .isEqualTo(2.0d);
    }

    @Test
    void recordSubmitted_hidden_incrementsCounterWithActionTag() {
        metrics.recordSubmitted(RatingAction.HIDDEN);

        assertThat(registry.get("rating_submitted_total").tag("action", "HIDDEN").counter().count())
                .isEqualTo(1.0d);
    }
}
