package com.shop.productservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.productservice.service.ProductMediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.annotation.DirtiesContext;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Media lifecycle consumer (media epic spec D4): the fleet serializer
 * DOUBLE-ENCODES the outbox payload string (JsonKafkaSerializer serializes the
 * JSON String into a JSON string token) — the consumer must unwrap before
 * binding. T4 GATE: a real double-encoded record must reach the handler as a
 * typed event; unknown eventTypes are ack-skipped; handler failures never
 * escape the listener (ack-always containment, rating-consumer precedent).
 */
@ExtendWith(MockitoExtension.class)
@EmbeddedKafka(partitions = 1, topics = "media.lifecycle.v1")
@DirtiesContext
class MediaDeletedConsumerTest {

    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock ProductMediaService productMediaService;

    private MediaDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MediaDeletedConsumer(productMediaService, objectMapper);
    }

    private String payload(String eventType) throws Exception {
        var node = new ObjectMapper().createObjectNode()
            .put("eventType", eventType)
            .put("mediaId", MEDIA_ID.toString())
            .put("sha256", "abc")
            .put("contentType", "image/jpeg")
            .put("canonicalPath", "/api/v1/medias/" + MEDIA_ID)
            .put("occurredAt", "2026-09-01T10:00:00Z");
        return objectMapper.writeValueAsString(node);
    }

    @Test
    @DisplayName("double-encoded MediaDeleted unwraps and clears the product reference")
    void doubleEncodedMediaDeleted_unwrapsAndDispatches() throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString(payload("MediaDeleted")),
            new MessageHeaders(new HashMap<>()));

        verify(productMediaService).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("single-encoded MediaDeleted also dispatches (shape-tolerant unwrap)")
    void singleEncodedMediaDeleted_dispatches() throws Exception {
        consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>()));

        verify(productMediaService).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("unknown eventType (MediaCreated) is ack-skipped — no clear")
    void mediaCreatedEventType_ackSkipped() throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString(payload("MediaCreated")),
            new MessageHeaders(new HashMap<>()));

        verify(productMediaService, never()).clearReference(any());
    }

    @Test
    @DisplayName("malformed JSON never throws out of the listener (ack-always containment)")
    void malformedPayload_neverThrows() {
        assertThatCode(() -> consumer.onMessage("{\"broken\"", new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productMediaService, never()).clearReference(any());
    }

    @Test
    @DisplayName("double-encoded malformed inner payload never throws either")
    void doubleEncodedMalformed_neverThrows() throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString("{\\\"broken\\\""),
            new MessageHeaders(new HashMap<>()));

        verify(productMediaService, never()).clearReference(any());
    }

    @Test
    @DisplayName("handler failure is contained — the listener must not throw")
    void handlerFailure_contained() throws Exception {
        doThrow(new RuntimeException("db down")).when(productMediaService).clearReference(any());

        assertThatCode(() -> consumer.onMessage(
                objectMapper.writeValueAsString(payload("MediaDeleted")),
                new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();
    }

    // --- H-3: bounded in-consumer retry for TRANSIENT failures ---

    private void transientFailureThenSuccess(int failingAttempts) throws Exception {
        var stub = when(productMediaService.clearReference(MEDIA_ID))
            .thenThrow(new QueryTimeoutException("db blip"));
        for (int i = 1; i < failingAttempts; i++) {
            stub = stub.thenThrow(new QueryTimeoutException("db blip"));
        }
        stub.thenReturn(0);

        assertThatCode(() -> consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productMediaService, times(MediaDeletedConsumer.MAX_ATTEMPTS)).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("transient failure → retried in-consumer → success on a later attempt (clear lands)")
    void transientFailure_retried_thenSucceeds() throws Exception {
        transientFailureThenSuccess(MediaDeletedConsumer.MAX_ATTEMPTS - 1);
    }

    @Test
    @DisplayName("first attempt succeeds immediately — no retry overhead")
    void firstAttemptSucceeds_noRetry() throws Exception {
        consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>()));

        verify(productMediaService, times(1)).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("transient failure exhausted after MAX_ATTEMPTS → ERROR + ack (no throw, posture preserved)")
    void transientFailure_exhausted_errorAndAck() throws Exception {
        doThrow(new QueryTimeoutException("db down"))
            .when(productMediaService).clearReference(MEDIA_ID);

        assertThatCode(() -> consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productMediaService, times(MediaDeletedConsumer.MAX_ATTEMPTS)).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("another TransientDataAccessException subclass (deadlock) is also retryable")
    void otherTransientSubclass_isRetryable() throws Exception {
        doThrow(new org.springframework.dao.DeadlockLoserDataAccessException("deadlock", null))
            .when(productMediaService).clearReference(MEDIA_ID);

        assertThatCode(() -> consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productMediaService, times(MediaDeletedConsumer.MAX_ATTEMPTS)).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("plain (non-transient) DataAccessException is NOT retried — narrowed to TransientDataAccessException")
    void nonTransientDataAccessException_immediateAck_noRetry() throws Exception {
        doThrow(new DataAccessException("constraint violation / bad grammar — not transient") {})
            .when(productMediaService).clearReference(MEDIA_ID);

        assertThatCode(() -> consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        // permanent failure for this payload — retrying cannot succeed, ack immediately
        verify(productMediaService, times(1)).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("NON-transient failure (not DataAccessException) → immediate ERROR + ack, no retry")
    void nonTransientFailure_immediateAck_noRetry() throws Exception {
        doThrow(new IllegalStateException("unexpected"))
            .when(productMediaService).clearReference(MEDIA_ID);

        assertThatCode(() -> consumer.onMessage(payload("MediaDeleted"), new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productMediaService, times(1)).clearReference(MEDIA_ID);
    }

    @Test
    @DisplayName("poison bytes still ack-skip IMMEDIATELY — no retry on parse failure")
    void poisonPayload_immediateAckSkip_noRetry() {
        assertThatCode(() -> consumer.onMessage("{\"broken\"", new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productMediaService, never()).clearReference(any());
    }
}
