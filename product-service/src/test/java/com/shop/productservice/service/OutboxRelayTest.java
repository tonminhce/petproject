package com.shop.productservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.repository.OutboxEventRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay publishes the claimed batch in one fan-out and marks every row
 * by its OWN outcome. A record that failed blocks the records behind it of
 * the same product only, so a consumer never sees a later event of an
 * aggregate before an earlier one.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String TOPIC = "shop.product.lifecycle.v1";

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaMessagePublisher kafkaPublisher;
    @Mock private ProductMetrics metrics;

    private OutboxRelay relay;

    private final UUID productId = UUID.randomUUID();
    private final UUID otherProductId = UUID.randomUUID();

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
        relay = new OutboxRelay(outboxRepo, kafkaPublisher, metrics, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
    }

    private OutboxEvent pendingEvent(Long id, UUID aggregate, int retryCount) {
        return OutboxEvent.builder()
                .id(id)
                .eventId(UUID.randomUUID().toString())
                .aggregateType("Product")
                .aggregateId(aggregate)
                .eventType("product.created.v1")
                .topic(TOPIC)
                .payload("{\"productId\":\"" + productId + "\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(retryCount)
                .build();
    }

    private void stubClaim(OutboxEvent... events) {
        // One stubbing with a sequence: consecutive when(...).thenReturn(...)
        // calls on the same mock would REPLACE each other, leaving only the
        // last one (the empty claim) in place.
        Optional<OutboxEvent>[] sequence = new Optional[events.length + 1];
        for (int i = 0; i < events.length; i++) {
            sequence[i] = Optional.of(events[i]);
        }
        sequence[events.length] = Optional.empty();
        when(outboxRepo.claimOnePending(OutboxStatus.PENDING))
                .thenReturn(sequence[0], Arrays.copyOfRange(sequence, 1, sequence.length));
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
    void relay_publishesTheWholeClaimedBatchInOneCall() {
        OutboxEvent first = pendingEvent(1L, productId, 0);
        OutboxEvent second = pendingEvent(2L, otherProductId, 0);
        stubClaim(first, second);
        stubBatch(List.of(first, second), true, true);

        relay.relay();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(kafkaPublisher).publishBatch(captor.capture(), eq(5_000L), any());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).key()).isEqualTo(productId.toString());
        assertThat(captor.getValue().get(1).key()).isEqualTo(otherProductId.toString());

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(first.getSentAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(first.getRetryCount()).isZero();
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void aFailedRecord_blocksTheLaterRecordsOfTheSameProductOnly() {
        OutboxEvent failed = pendingEvent(1L, productId, 0);
        OutboxEvent sameProduct = pendingEvent(2L, productId, 0);
        OutboxEvent otherProduct = pendingEvent(3L, otherProductId, 0);
        stubClaim(failed, sameProduct, otherProduct);
        stubBatch(List.of(failed, sameProduct, otherProduct), false, true, true);

        relay.relay();

        assertThat(failed.getStatus())
                .as("the failed record goes back to the retry set")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("kafka down");

        assertThat(sameProduct.getStatus())
                .as("a record behind the failed one of the SAME product must be retried, "
                        + "otherwise the consumer sees the later event first")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(sameProduct.getRetryCount()).isEqualTo(1);

        assertThat(otherProduct.getStatus())
                .as("another product is not blocked")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(otherProduct.getRetryCount()).isZero();
    }

    @Test
    void relay_maxRetriesExhausted_marksFailed() {
        OutboxEvent event = pendingEvent(1L, productId, 9);
        stubClaim(event);
        stubBatch(List.of(event), false);

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(10);
        assertThat(event.getLastError()).isEqualTo("kafka down");
        assertThat(event.getSentAt()).isNull();
    }

    @Test
    void claimedRowsArePublishedInAscendingIdOrder() {
        // The database does not return the rows in id order, so the relay has
        // to sort them: a scrambled batch must come out ascending, because
        // ascending id IS the production order (the claim takes the lowest
        // pending id first).
        OutboxEvent first = pendingEvent(1L, productId, 0);
        OutboxEvent second = pendingEvent(2L, productId, 0);
        OutboxEvent third = pendingEvent(3L, otherProductId, 0);

        List<OutboxEvent> ordered = OutboxRelay.orderById(
                new ArrayList<>(List.of(third, first, second)));

        assertThat(ordered).extracting(OutboxEvent::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void relay_emptyPending_noPublishNoSave() {
        when(outboxRepo.claimOnePending(OutboxStatus.PENDING)).thenReturn(Optional.empty());

        relay.relay();

        verify(kafkaPublisher, never()).publishBatch(anyList(), anyLong(), any());
        verify(outboxRepo, never()).save(any(OutboxEvent.class));
    }
}
