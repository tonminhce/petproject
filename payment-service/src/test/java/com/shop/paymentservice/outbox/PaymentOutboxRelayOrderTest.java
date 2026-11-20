package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxRelayOrderTest {

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private KafkaMessagePublisher kafkaPublisher;

    private PaymentOutboxRelay relay;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus(true);
            }
            @Override
            public void commit(TransactionStatus status) { }
            @Override
            public void rollback(TransactionStatus status) { }
        };
        relay = new PaymentOutboxRelay(outboxRepo, kafkaPublisher, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
    }

    @Test
    void claimedRowsAreOrderedByAscendingId() {
        OutboxEvent first = row(1L);
        OutboxEvent second = row(2L);
        OutboxEvent third = row(3L);

        List<OutboxEvent> scrambled = new ArrayList<>(List.of(third, first, second));

        List<OutboxEvent> ordered = PaymentOutboxRelay.orderById(scrambled);

        assertThat(ordered).extracting(OutboxEvent::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void orderingIsStableForRowsThatShareAnAggregate() {
        UUID aggregate = UUID.randomUUID();
        OutboxEvent captured = row(1L, aggregate);
        OutboxEvent refunded = row(2L, aggregate);

        List<OutboxEvent> ordered = PaymentOutboxRelay.orderById(new ArrayList<>(List.of(refunded, captured)));

        assertThat(ordered).extracting(OutboxEvent::getId).containsExactly(1L, 2L);
        assertThat(ordered.get(0).getAggregateId()).isEqualTo(aggregate);
    }

    @Test
    void aFailedCapture_keepsTheLaterRefundOfTheSamePaymentPending() {
        UUID payment = UUID.randomUUID();
        OutboxEvent capture = row(1L, payment);
        OutboxEvent refund = row(2L, payment);
        stubClaim(capture, refund);
        stubPublish(List.of(capture, refund), false, true);

        relay.relay();

        assertThat(capture.getStatus())
                .as("the failed capture returns to the retry set")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(capture.getRetryCount()).isEqualTo(1);
        assertThat(refund.getStatus())
                .as("the refund of the same payment must not be marked sent while its capture is unpublished")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(refund.getSentAt())
                .as("the blocked refund must not carry a sent timestamp")
                .isNull();
    }

    @Test
    void aFailedCapture_doesNotBlockAnotherPayment() {
        UUID payment = UUID.randomUUID();
        UUID otherPayment = UUID.randomUUID();
        OutboxEvent capture = row(1L, payment);
        OutboxEvent otherCapture = row(2L, otherPayment);
        stubClaim(capture, otherCapture);
        stubPublish(List.of(capture, otherCapture), false, true);

        relay.relay();

        assertThat(capture.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(otherCapture.getStatus())
                .as("another payment is not blocked by the failed capture")
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void aFullyPublishedBatch_marksEveryRowSent() {
        UUID payment = UUID.randomUUID();
        OutboxEvent capture = row(1L, payment);
        OutboxEvent refund = row(2L, payment);
        stubClaim(capture, refund);
        stubPublish(List.of(capture, refund), true, true);

        relay.relay();

        assertThat(capture.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(refund.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(refund.getSentAt()).isNotNull();
    }

    @Test
    void aClaimedRow_carriesTheLeaseOfThisInstance() {
        OutboxEvent capture = row(1L);
        stubClaim(capture);
        AtomicReference<String> ownerWhileLeased = new AtomicReference<>();
        AtomicReference<Instant> claimedWhileLeased = new AtomicReference<>();
        when(kafkaPublisher.publishBatch(anyList(), anyLong(), any())).thenAnswer(invocation -> {
            ownerWhileLeased.set(capture.getLeaseOwner());
            claimedWhileLeased.set(capture.getClaimedAt());
            return new BatchOutcome(1, 1, true,
                    List.of(new BatchOutcome.RecordOutcome(0, true, null)));
        });
        when(outboxRepo.findByIdForUpdate(capture.getId())).thenReturn(Optional.of(capture));

        relay.relay();

        assertThat(ownerWhileLeased.get())
                .as("a claimed row must carry the owner of the claim")
                .isNotBlank();
        assertThat(claimedWhileLeased.get())
                .as("a claimed row must carry the lease timestamp of the claim")
                .isNotNull();
    }

    @Test
    void releaseOwnClaims_finalisesThePublishedLeaseAndNeverHandsItBack() {
        OutboxEvent published = row(1L);
        stubClaim(published);
        stubPublish(List.of(published), true);
        when(outboxRepo.findByIdForUpdate(published.getId()))
                .thenThrow(new IllegalStateException("the database is gone"));

        assertThatThrownBy(relay::relay).isInstanceOf(IllegalStateException.class);

        relay.releaseOwnClaims();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> finalised = ArgumentCaptor.forClass(List.class);
        verify(outboxRepo).finalisePublishedClaims(finalised.capture(), eq(OutboxStatus.SENDING),
                eq(OutboxStatus.SENT), anyString(), any(Instant.class));
        assertThat(finalised.getValue()).containsExactly(published.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> excluded = ArgumentCaptor.forClass(List.class);
        verify(outboxRepo).releaseOwnedClaims(eq(OutboxStatus.SENDING), eq(OutboxStatus.PENDING),
                anyString(), excluded.capture());
        assertThat(excluded.getValue())
                .as("a lease whose event was already published must never go back to PENDING")
                .contains(published.getId());
    }

    private void stubClaim(OutboxEvent... events) {
        List<UUID> aggregates = new ArrayList<>(new LinkedHashSet<>(List.of(events).stream()
                .map(OutboxEvent::getAggregateId).toList()));
        when(outboxRepo.findClaimableAggregates(anyCollection(), anyInt())).thenReturn(aggregates);
        when(outboxRepo.claimAggregateEvents(any(UUID.class), anyCollection(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    UUID aggregateId = invocation.getArgument(0);
                    return List.of(events).stream()
                            .filter(event -> event.getAggregateId().equals(aggregateId))
                            .toList();
                });
    }

    private void stubPublish(List<OutboxEvent> rows, boolean... delivered) {
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

    private static OutboxEvent row(Long id) {
        return row(id, UUID.randomUUID());
    }

    private static OutboxEvent row(Long id, UUID aggregate) {
        return OutboxEvent.builder()
                .id(id)
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(aggregate)
                .eventType("payment.test.v1")
                .topic("shop.payment.test.v1")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }
}
