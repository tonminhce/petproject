package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaOutboxRelayTest {

    private static final String TOPIC = "media.lifecycle.v1";

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaMessagePublisher kafkaPublisher;

    private static final Pageable PAGE_REQUEST = PageRequest.of(0, 100);

    private MediaOutboxRelay relay;

    private final UUID mediaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        relay = new MediaOutboxRelay(outboxRepo, kafkaPublisher);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
    }

    private OutboxEvent pendingEvent(int retryCount) {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("media")
                .aggregateId(mediaId)
                .eventType("MediaCreated")
                .topic(TOPIC)
                .payload("{\"eventType\":\"MediaCreated\",\"mediaId\":\"" + mediaId + "\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(retryCount)
                .build();
    }

    @Test
    void relay_success_marksSentWithSentAtAndKeepsRetryCount() {
        OutboxEvent event = pendingEvent(0);
        when(outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PAGE_REQUEST))
                .thenReturn(List.of(event));

        relay.relay();

        // Kafka key = mediaId (spec D4 per-media partition ordering)
        verify(kafkaPublisher).publish(TOPIC, mediaId.toString(), event.getPayload());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(saved.getSentAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    void relay_publishesWhateverEventTypeRowCarries() {
        OutboxEvent event = pendingEvent(0);
        event.setEventType("MediaDeleted");
        when(outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PAGE_REQUEST))
                .thenReturn(List.of(event));

        relay.relay();

        // Relay is type-agnostic — it relays the row verbatim; consumers ack-skip unknown.
        verify(kafkaPublisher).publish(TOPIC, mediaId.toString(), event.getPayload());
    }

    @Test
    void relay_publishFailure_incrementsRetryAndBreaksLoop() {
        OutboxEvent first = pendingEvent(0);
        OutboxEvent second = pendingEvent(0);
        when(outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PAGE_REQUEST))
                .thenReturn(List.of(first, second));
        doThrow(new RuntimeException("kafka down")).when(kafkaPublisher)
                .publish(TOPIC, mediaId.toString(), first.getPayload());

        relay.relay();

        verify(kafkaPublisher, times(1)).publish(any(String.class), any(String.class), any(Object.class));
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(first.getLastError()).isEqualTo("kafka down");
        // Second event untouched — loop breaks after first failure
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getRetryCount()).isZero();
        assertThat(second.getLastError()).isNull();
        verify(outboxRepo, times(1)).save(first);
        verify(outboxRepo, never()).save(second);
    }

    @Test
    void relay_maxRetriesExhausted_marksFailed() {
        OutboxEvent event = pendingEvent(9);
        when(outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PAGE_REQUEST))
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("kafka still down")).when(kafkaPublisher)
                .publish(TOPIC, mediaId.toString(), event.getPayload());

        relay.relay();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(saved.getRetryCount()).isEqualTo(10);
        assertThat(saved.getLastError()).isEqualTo("kafka still down");
        assertThat(saved.getSentAt()).isNull();
    }

    @Test
    void relay_emptyPending_noPublishNoSave() {
        when(outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PAGE_REQUEST))
                .thenReturn(List.of());

        relay.relay();

        verifyNoInteractions(kafkaPublisher);
        verify(outboxRepo, never()).save(any(OutboxEvent.class));
    }
}
