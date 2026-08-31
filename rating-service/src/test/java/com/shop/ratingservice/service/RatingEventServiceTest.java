package com.shop.ratingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.entity.Rating;
import com.shop.ratingservice.metrics.RatingMetrics;
import com.shop.ratingservice.outbox.OutboxEvent;
import com.shop.ratingservice.outbox.OutboxEventRepository;
import com.shop.ratingservice.repository.RatingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RatingEventServiceTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private OutboxEventRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RatingEventService service;

    private final UUID productId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RatingEventService(ratingRepository, outboxRepository, objectMapper,
                new RatingMetrics(new SimpleMeterRegistry()));
    }

    private Rating rating(int value, boolean verified, boolean hidden) {
        return Rating.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .userId(userId)
                .rating(value)
                .comment("Solid build quality, works as advertised")
                .verified(verified)
                .hidden(hidden)
                .build();
    }

    @Test
    void record_created_writes13FieldPendingPayload() throws Exception {
        when(ratingRepository.findAggregateByProductId(productId))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("4.5"), 1L}));
        Rating rating = rating(5, true, false);

        service.record(rating, RatingAction.CREATED);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getAggregateType()).isEqualTo("rating");
        // Spec D4: aggregateId = productId — it doubles as the Kafka
        // partition key (per-product ordering); the relay keys on it.
        assertThat(event.getAggregateId()).isEqualTo(productId);
        assertThat(event.getEventType()).isEqualTo("rating.submitted.v1");
        assertThat(event.getTopic()).isEqualTo("shop.rating.lifecycle.v1");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();

        // Spec D4 — exactly these 13 fields, verbatim names.
        JsonNode json = objectMapper.readTree(event.getPayload());
        assertThat(json.size()).isEqualTo(13);
        assertThat(UUID.fromString(json.get("eventId").textValue()).toString()).isEqualTo(event.getEventId());
        assertThat(json.get("eventType").textValue()).isEqualTo("rating.submitted.v1");
        assertThat(Instant.parse(json.get("occurredAt").textValue())).isBeforeOrEqualTo(Instant.now());
        assertThat(UUID.fromString(json.get("ratingId").textValue())).isEqualTo(rating.getId());
        assertThat(UUID.fromString(json.get("productId").textValue())).isEqualTo(productId);
        assertThat(UUID.fromString(json.get("userId").textValue())).isEqualTo(userId);
        assertThat(json.get("rating").intValue()).isEqualTo(5);
        assertThat(json.get("comment").textValue()).isEqualTo("Solid build quality, works as advertised");
        assertThat(json.get("verified").booleanValue()).isTrue();
        assertThat(json.get("action").textValue()).isEqualTo("CREATED");
        assertThat(json.get("visible").booleanValue()).isTrue();
        assertThat(json.get("avgRating").decimalValue()).isEqualByComparingTo("4.50");
        // Scale is a write-side guarantee — JSON numbers are scale-free on
        // re-parse (Jackson strips trailing zeroes), so assert the raw text.
        assertThat(event.getPayload()).contains("\"avgRating\":4.50");
        assertThat(json.get("ratingCount").intValue()).isEqualTo(1);
    }

    @Test
    void record_updated_recomputesSnapshot() throws Exception {
        when(ratingRepository.findAggregateByProductId(productId))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("3.5"), 2L}));
        Rating rating = rating(2, true, false);

        service.record(rating, RatingAction.UPDATED);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("action").textValue()).isEqualTo("UPDATED");
        assertThat(json.get("avgRating").decimalValue()).isEqualByComparingTo("3.50");
        assertThat(captor.getValue().getPayload()).contains("\"avgRating\":3.50");
        assertThat(json.get("ratingCount").intValue()).isEqualTo(2);
        assertThat(json.get("visible").booleanValue()).isTrue();
    }

    @Test
    void record_doubleValuedAggregate_scalesHalfUp() throws Exception {
        // Hibernate may hand back AVG over an integral column as a Double —
        // must still normalize to scale-2 HALF_UP.
        when(ratingRepository.findAggregateByProductId(productId))
                .thenReturn(List.<Object[]>of(new Object[]{4.3333333333d, 3L}));
        Rating rating = rating(4, true, false);

        service.record(rating, RatingAction.UPDATED);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("avgRating").decimalValue()).isEqualByComparingTo("4.33");
        assertThat(json.get("avgRating").decimalValue().scale()).isEqualTo(2);
    }

    @Test
    void record_hidden_payloadCarriesHiddenActionAndInvisibleFlag() throws Exception {
        // Snapshot excludes the hidden row itself — aggregate reflects the
        // remaining visible ratings (T5 carry-over gap test).
        when(ratingRepository.findAggregateByProductId(productId))
                .thenReturn(List.<Object[]>of(new Object[]{new BigDecimal("4.0"), 2L}));
        Rating rating = rating(5, true, true);

        service.record(rating, RatingAction.HIDDEN);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("action").textValue()).isEqualTo("HIDDEN");
        assertThat(json.get("visible").booleanValue()).isFalse();
    }
}
