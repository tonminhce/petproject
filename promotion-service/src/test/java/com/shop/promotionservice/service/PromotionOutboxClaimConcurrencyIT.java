package com.shop.promotionservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.promotionservice.entity.OutboxEvent;
import com.shop.promotionservice.repository.OutboxEventRepository;
import com.shop.promotionservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionOutboxClaimConcurrencyIT extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 4;
    private static final int PENDING_EVENTS = 12;

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("promotion.outbox.poll-interval-ms", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired PromotionMetrics metrics;

    @BeforeEach
    void clearOutbox() {
        outboxRepo.deleteAll();
    }

    @Test
    void twoRelayInstances_publishDisjointEventsAndEveryEventExactlyOnce() throws Exception {
        RecordingPublisher publisherA = new RecordingPublisher();
        RecordingPublisher publisherB = new RecordingPublisher();
        PromotionOutboxRelay relayA = relayWith(publisherA);
        PromotionOutboxRelay relayB = relayWith(publisherB);

        List<String> pending = seedPendingEvents(PENDING_EVENTS);

        for (int round = 0; round < PENDING_EVENTS; round++) {
            relayConcurrently(relayA, relayB);
            if (dueCount() == 0) {
                break;
            }
        }

        List<String> publishedByA = publisherA.payloads();
        List<String> publishedByB = publisherB.payloads();
        List<String> published = new ArrayList<>(publishedByA);
        published.addAll(publishedByB);

        assertThat(publishedByA).as("the first replica must publish its own share").isNotEmpty();
        assertThat(publishedByB).as("the second replica must publish its own share").isNotEmpty();
        assertThat(published).as("no event may be published twice").doesNotHaveDuplicates();
        assertThat(new HashSet<>(published))
                .as("the union of both replicas must cover every pending event")
                .containsExactlyInAnyOrderElementsOf(pending);
        assertThat(dueCount())
                .as("every pending event must leave the outbox").isZero();
        assertThat(sentCount())
                .as("every pending event must be marked sent exactly once")
                .isEqualTo(PENDING_EVENTS);
    }

    @Test
    void aClaimOnOneAggregateDoesNotBlockAnotherAggregate() throws Exception {
        OutboxEvent locked = outboxRepo.saveAndFlush(pendingEvent());
        OutboxEvent other = outboxRepo.saveAndFlush(pendingEvent());
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch headLocked = new CountDownLatch(1);
        CountDownLatch peerClaimed = new CountDownLatch(1);
        CountDownLatch holderCommitted = new CountDownLatch(1);
        AtomicReference<List<OutboxEvent>> claimWhileLocked = new AtomicReference<>();
        AtomicReference<List<OutboxEvent>> claimAfterCommit = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            tx.executeWithoutResult(status -> {
                outboxRepo.claimAggregateEvents(locked.getAggregateId(), pendingStatuses(),
                        OutboxStatus.SENDING.name(), 1);
                headLocked.countDown();
                await(peerClaimed);
            });
            holderCommitted.countDown();
        });
        Thread peer = new Thread(() -> {
            await(headLocked);
            claimWhileLocked.set(tx.execute(t -> outboxRepo.claimAggregateEvents(
                    other.getAggregateId(), pendingStatuses(), OutboxStatus.SENDING.name(), 1)));
            peerClaimed.countDown();
            await(holderCommitted);
            claimAfterCommit.set(tx.execute(t -> outboxRepo.claimAggregateEvents(
                    locked.getAggregateId(), pendingStatuses(), OutboxStatus.SENDING.name(), 1)));
        });
        holder.start();
        peer.start();
        holder.join(30_000);
        peer.join(30_000);

        assertThat(holder.isAlive()).isFalse();
        assertThat(peer.isAlive()).isFalse();

        assertThat(claimWhileLocked.get())
                .as("a locked aggregate must not block the claim of another aggregate")
                .extracting(OutboxEvent::getId)
                .containsExactly(other.getId());
        assertThat(claimAfterCommit.get())
                .as("the locked aggregate must be claimable again once the lock is released")
                .extracting(OutboxEvent::getId)
                .containsExactly(locked.getId());
    }

    @Test
    void anAggregateThatIsInFlightIsNotClaimedByAnotherInstance() {
        OutboxEvent inFlight = outboxRepo.saveAndFlush(pendingEvent());
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            List<OutboxEvent> claimed = outboxRepo.claimAggregateEvents(inFlight.getAggregateId(),
                    pendingStatuses(), OutboxStatus.SENDING.name(), 1);
            assertThat(claimed).hasSize(1);
            claimed.get(0).setStatus(OutboxStatus.SENDING);
            claimed.get(0).setClaimedAt(Instant.now());
            outboxRepo.saveAll(claimed);
        });

        List<OutboxEvent> second = tx.execute(t -> outboxRepo.claimAggregateEvents(inFlight.getAggregateId(),
                pendingStatuses(), OutboxStatus.SENDING.name(), 1));

        assertThat(second)
                .as("an aggregate with an event in flight must not be claimed again")
                .isEmpty();
    }

    private List<String> pendingStatuses() {
        return List.of(OutboxStatus.PENDING.name());
    }

    private PromotionOutboxRelay relayWith(RecordingPublisher publisher) {
        PromotionOutboxRelay relay = new PromotionOutboxRelay(outboxRepo, publisher, metrics, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", BATCH_SIZE);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
        return relay;
    }

    private void relayConcurrently(PromotionOutboxRelay relayA, PromotionOutboxRelay relayB) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        Thread a = new Thread(() -> {
            await(start);
            try {
                relayA.relay();
            } catch (Throwable failure) {
                failureA.set(failure);
            }
        });
        Thread b = new Thread(() -> {
            await(start);
            try {
                relayB.relay();
            } catch (Throwable failure) {
                failureB.set(failure);
            }
        });
        a.start();
        b.start();
        start.countDown();
        a.join(30_000);
        b.join(30_000);
        assertThat(a.isAlive()).isFalse();
        assertThat(b.isAlive()).isFalse();
        assertThat(failureA.get()).isNull();
        assertThat(failureB.get()).isNull();
    }

    private long dueCount() {
        return outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, 1000)).size();
    }

    private long sentCount() {
        return outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.SENT, PageRequest.of(0, 1000)).size();
    }

    private List<String> seedPendingEvents(int count) {
        List<String> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            OutboxEvent event = pendingEvent();
            outboxRepo.saveAndFlush(event);
            payloads.add(event.getPayload());
        }
        return payloads;
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("promotion")
                .aggregateId(UUID.randomUUID())
                .eventType("promotion.updated.v1")
                .topic("shop.promotion.events.v1")
                .payload("{\"payload\":\"" + UUID.randomUUID() + "\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class RecordingPublisher extends KafkaMessagePublisher {

        private final List<OutboxMessage> published = Collections.synchronizedList(new ArrayList<>());

        RecordingPublisher() {
            super((KafkaTemplate<String, String>) null);
        }

        @Override
        public BatchOutcome publishBatch(List<OutboxMessage> records, long timeout, TimeUnit unit) {
            published.addAll(records);
            List<BatchOutcome.RecordOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                outcomes.add(new BatchOutcome.RecordOutcome(i, true, null));
            }
            return new BatchOutcome(records.size(), records.size(), true, outcomes);
        }

        List<String> payloads() {
            List<String> values = new ArrayList<>();
            synchronized (published) {
                for (OutboxMessage message : published) {
                    values.add(String.valueOf(message.value()));
                }
            }
            return values;
        }
    }
}
