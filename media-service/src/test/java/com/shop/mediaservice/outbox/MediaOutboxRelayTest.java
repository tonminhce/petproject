package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The relay publishes the claimed batch in one fan-out and marks every row
 * by its OWN outcome. A record that failed blocks the records behind it of
 * the same media only, so a consumer never sees a later event of a media
 * before an earlier one.
 *
 * <p>This service's outbox has two properties the tests pin: FAILED is not
 * terminal (it is replayed until the retention window ages it to DEAD), and
 * the relay is type-agnostic (it relays whatever event type the row
 * carries).</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaOutboxRelayTest {

    private static final String TOPIC = "media.lifecycle.v1";
    private static final List<OutboxStatus> DUE_STATUSES = List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaMessagePublisher kafkaPublisher;

    private MediaOutboxRelay relay;

    private final UUID mediaId = UUID.randomUUID();
    private final UUID otherMediaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                // No-op - TransactionTemplate needs a non-null status to commit.
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
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
    }

    private OutboxEvent event(Long id, OutboxStatus status, int retryCount, UUID aggregate) {
        return OutboxEvent.builder()
                .id(id)
                .eventId(UUID.randomUUID().toString())
                .aggregateType("media")
                .aggregateId(aggregate)
                .eventType("MediaCreated")
                .topic(TOPIC)
                .payload("{\"eventType\":\"MediaCreated\",\"mediaId\":\"" + aggregate + "\"}")
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

    private void stubBatch(List<OutboxEvent> rows, boolean... delivered) {
        when(outboxRepo.findAllById(anyList())).thenReturn(rows);
        List<BatchOutcome.RecordOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            outcomes.add(new BatchOutcome.RecordOutcome(i, i < delivered.length && delivered[i],
                    i < delivered.length && delivered[i] ? null : "kafka down"));
        }
        when(kafkaPublisher.publishBatch(anyList(), anyLong(), any()))
                .thenReturn(new BatchOutcome(rows.size(),
                        (int) outcomes.stream().filter(BatchOutcome.RecordOutcome::success).count(),
                        true, outcomes));
        for (OutboxEvent row : rows) {
            when(outboxRepo.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        }
    }

    @Test
    void relay_success_marksSentWithSentAtAndKeepsRetryCount() {
        OutboxEvent event = event(1L, OutboxStatus.PENDING, 0, mediaId);
        due(event);
        stubBatch(List.of(event), true);

        relay.relay();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxMessage>> captor = ArgumentCaptor.forClass(List.class);
        // Kafka key = mediaId (per-media partition ordering)
        verify(kafkaPublisher).publishBatch(captor.capture(), anyLong(), any());
        assertThat(captor.getValue().get(0).key()).isEqualTo(mediaId.toString());
        assertThat(captor.getValue().get(0).value()).isEqualTo(event.getPayload());

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void relay_publishesWhateverEventTypeRowCarries() {
        OutboxEvent event = event(1L, OutboxStatus.PENDING, 0, mediaId);
        event.setEventType("MediaDeleted");
        due(event);
        stubBatch(List.of(event), true);

        relay.relay();

        // Relay is type-agnostic - it relays the row verbatim; consumers ack-skip unknown.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(kafkaPublisher).publishBatch(captor.capture(), anyLong(), any());
        assertThat(captor.getValue().get(0).value()).isEqualTo(event.getPayload());
    }

    @Test
    void relay_publishFailure_incrementsRetryAndKeepsTheRowPending() {
        OutboxEvent event = event(1L, OutboxStatus.PENDING, 0, mediaId);
        due(event);
        stubBatch(List.of(event), false);

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("kafka down");
    }

    @Test
    void relay_maxRetriesExhausted_marksFailed() {
        OutboxEvent event = event(1L, OutboxStatus.PENDING, 9, mediaId);
        due(event);
        stubBatch(List.of(event), false);

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(10);
        assertThat(event.getLastError()).isEqualTo("kafka down");
        assertThat(event.getSentAt()).isNull();
        assertThat(event.getFailedAt())
                .as("a parked row is stamped so the retention window can age it to DEAD")
                .isNotNull();
    }

    @Test
    @DisplayName("FAILED row is NOT terminal - picked up again next cycle, published + SENT")
    void relay_failedRowIsReplayedOnNextCycle() {
        OutboxEvent event = event(1L, OutboxStatus.FAILED, 10, mediaId);
        event.setFailedAt(Instant.now().minusSeconds(60));
        due(event);
        stubBatch(List.of(event), true);

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(event.getLastError()).isNull();
        assertThat(event.getFailedAt())
                .as("a replayed row leaves the aging clock")
                .isNull();
    }

    @Test
    @DisplayName("FAILED row that fails again stays FAILED - one bounded attempt per cycle")
    void relay_failedRowReplayFailure_staysFailed() {
        OutboxEvent event = event(1L, OutboxStatus.FAILED, 10, mediaId);
        due(event);
        stubBatch(List.of(event), false);

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);  // parked for the next cycle
        assertThat(event.getRetryCount()).isEqualTo(11);
        assertThat(event.getLastError()).isEqualTo("kafka down");
    }

    @Test
    void aFailedRecord_blocksTheLaterRecordsOfTheSameMediaOnly() {
        OutboxEvent failed = event(1L, OutboxStatus.PENDING, 0, mediaId);
        OutboxEvent sameMedia = event(2L, OutboxStatus.PENDING, 0, mediaId);
        OutboxEvent otherMedia = event(3L, OutboxStatus.PENDING, 0, otherMediaId);
        due(failed, sameMedia, otherMedia);
        stubBatch(List.of(failed, sameMedia, otherMedia), false, true, true);

        relay.relay();

        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getRetryCount()).isEqualTo(1);

        assertThat(sameMedia.getStatus())
                .as("a record behind the failed one of the SAME media must be retried, "
                        + "otherwise the consumer sees the later event first")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(sameMedia.getRetryCount()).isEqualTo(1);

        assertThat(otherMedia.getStatus())
                .as("another media is not blocked")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(otherMedia.getRetryCount()).isZero();
    }

    @Test
    void claimedRowsArePublishedInAscendingIdOrder() {
        // The database does not return the rows in id order, so the relay has
        // to sort them: a scrambled batch must come out ascending, because
        // ascending id IS the production order (the claim takes the lowest
        // due id first).
        OutboxEvent first = event(1L, OutboxStatus.PENDING, 0, mediaId);
        OutboxEvent second = event(2L, OutboxStatus.PENDING, 0, mediaId);
        OutboxEvent third = event(3L, OutboxStatus.PENDING, 0, otherMediaId);

        List<OutboxEvent> ordered = MediaOutboxRelay.orderById(
                new ArrayList<>(List.of(third, first, second)));

        assertThat(ordered).extracting(OutboxEvent::getId).containsExactly(1L, 2L, 3L);
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
