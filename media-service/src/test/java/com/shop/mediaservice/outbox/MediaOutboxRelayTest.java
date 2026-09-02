package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C14 relay state machine — due rows (PENDING or replayable FAILED) come from
 * {@link OutboxEventRepository#claimOneDue} (one row per call). The
 * PlatformTransactionManager is a no-op stub (payment WebhookRetrySchedulerTest
 * precedent) because the assertions are about relay semantics, not transaction
 * propagation (that's verified by the claim-concurrency IT on a real DB).
 */
@ExtendWith(MockitoExtension.class)
class MediaOutboxRelayTest {

    private static final String TOPIC = "media.lifecycle.v1";
    private static final List<OutboxStatus> DUE_STATUSES = List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaMessagePublisher kafkaPublisher;

    private MediaOutboxRelay relay;

    private final UUID mediaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                // No-op — TransactionTemplate needs a non-null status to commit.
                return new SimpleTransactionStatus(true);
            }
            @Override
            public void commit(TransactionStatus status) { }
            @Override
            public void rollback(TransactionStatus status) { }
        };
        relay = new MediaOutboxRelay(outboxRepo, kafkaPublisher, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
    }

    private OutboxEvent event(OutboxStatus status, int retryCount) {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("media")
                .aggregateId(mediaId)
                .eventType("MediaCreated")
                .topic(TOPIC)
                .payload("{\"eventType\":\"MediaCreated\",\"mediaId\":\"" + mediaId + "\"}")
                .status(status)
                .retryCount(retryCount)
                .build();
    }

    private void due(OutboxEvent... events) {
        OngoingStubbing<Optional<OutboxEvent>> stub =
                when(outboxRepo.claimOneDue(DUE_STATUSES));
        for (OutboxEvent event : events) {
            stub = stub.thenReturn(Optional.of(event));
        }
        stub.thenReturn(Optional.empty());
    }

    @Test
    void relay_success_marksSentWithSentAtAndKeepsRetryCount() {
        OutboxEvent event = event(OutboxStatus.PENDING, 0);
        due(event);

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
        OutboxEvent event = event(OutboxStatus.PENDING, 0);
        event.setEventType("MediaDeleted");
        due(event);

        relay.relay();

        // Relay is type-agnostic — it relays the row verbatim; consumers ack-skip unknown.
        verify(kafkaPublisher).publish(TOPIC, mediaId.toString(), event.getPayload());
    }

    @Test
    void relay_publishFailure_incrementsRetryAndBreaksLoop() {
        OutboxEvent first = event(OutboxStatus.PENDING, 0);
        OutboxEvent second = event(OutboxStatus.PENDING, 0);
        due(first, second);
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
        OutboxEvent event = event(OutboxStatus.PENDING, 9);
        due(event);
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
    @DisplayName("FAILED row is NOT terminal — picked up again next cycle, published + SENT")
    void relay_failedRowIsReplayedOnNextCycle() {
        OutboxEvent event = event(OutboxStatus.FAILED, 10);
        due(event);

        relay.relay();

        verify(kafkaPublisher).publish(TOPIC, mediaId.toString(), event.getPayload());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(saved.getSentAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    @DisplayName("FAILED row that fails again stays FAILED — one bounded attempt per cycle")
    void relay_failedRowReplayFailure_staysFailed() {
        OutboxEvent event = event(OutboxStatus.FAILED, 10);
        due(event);
        doThrow(new RuntimeException("broker still down")).when(kafkaPublisher)
                .publish(TOPIC, mediaId.toString(), event.getPayload());

        relay.relay();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.FAILED); // parked for the next cycle
        assertThat(saved.getRetryCount()).isEqualTo(11);
        assertThat(saved.getLastError()).isEqualTo("broker still down");
    }

    @Test
    void relay_emptyPending_noPublishNoSave() {
        when(outboxRepo.claimOneDue(DUE_STATUSES))
                .thenReturn(Optional.empty());

        relay.relay();

        verifyNoInteractions(kafkaPublisher);
        verify(outboxRepo, never()).save(any(OutboxEvent.class));
    }
}
