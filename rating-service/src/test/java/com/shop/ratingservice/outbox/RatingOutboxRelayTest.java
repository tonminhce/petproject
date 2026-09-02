package com.shop.ratingservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
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
 * C14 relay state machine — the claim comes from
 * {@link OutboxEventRepository#claimOnePending} (one row per call). The
 * PlatformTransactionManager is a no-op stub (payment WebhookRetrySchedulerTest
 * precedent) because the assertions are about relay semantics, not transaction
 * propagation (that's verified by the claim-concurrency IT on a real DB).
 */
@ExtendWith(MockitoExtension.class)
class RatingOutboxRelayTest {

    private static final String TOPIC = "shop.rating.lifecycle.v1";

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaMessagePublisher kafkaPublisher;

    private RatingOutboxRelay relay;

    private final UUID productId = UUID.randomUUID();
    private final UUID ratingId = UUID.randomUUID();

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
        relay = new RatingOutboxRelay(outboxRepo, kafkaPublisher, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
    }

    private OutboxEvent pendingEvent(int retryCount) {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("rating")
                .aggregateId(productId)
                .eventType("rating.submitted.v1")
                .topic(TOPIC)
                .payload("{\"ratingId\":\"" + ratingId + "\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(retryCount)
                .build();
    }

    @Test
    void relay_success_marksSentWithSentAtAndKeepsRetryCount() {
        OutboxEvent event = pendingEvent(0);
        when(outboxRepo.claimOnePending(OutboxStatus.PENDING))
                .thenReturn(Optional.of(event))
                .thenReturn(Optional.empty());

        relay.relay();

        // Kafka key = productId (spec D4 per-product partition ordering)
        verify(kafkaPublisher).publish(TOPIC, productId.toString(), event.getPayload());
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(saved.getSentAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    void relay_publishFailure_incrementsRetryAndBreaksLoop() {
        OutboxEvent first = pendingEvent(0);
        OutboxEvent second = pendingEvent(0);
        when(outboxRepo.claimOnePending(OutboxStatus.PENDING))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("kafka down")).when(kafkaPublisher)
                .publish(TOPIC, productId.toString(), first.getPayload());

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
        when(outboxRepo.claimOnePending(OutboxStatus.PENDING))
                .thenReturn(Optional.of(event))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("kafka still down")).when(kafkaPublisher)
                .publish(TOPIC, productId.toString(), event.getPayload());

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
        when(outboxRepo.claimOnePending(OutboxStatus.PENDING))
                .thenReturn(Optional.empty());

        relay.relay();

        verifyNoInteractions(kafkaPublisher);
        verify(outboxRepo, never()).save(any(OutboxEvent.class));
    }
}
