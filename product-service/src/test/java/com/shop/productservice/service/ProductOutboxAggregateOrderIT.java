package com.shop.productservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProductOutboxAggregateOrderIT extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 1;
    private static final int DRAIN_ROUNDS = 10;

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("product.outbox.poll-interval-ms", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ProductMetrics metrics;

    @BeforeEach
    void clearOutbox() {
        outboxRepo.deleteAll();
    }

    @Test
    void twoRelayInstances_neverPublishTwoEventsOfTheSameAggregateOutOfOrder() throws Exception {
        UUID firstAggregate = UUID.randomUUID();
        UUID secondAggregate = UUID.randomUUID();
        seed(firstAggregate, "first-1");
        seed(firstAggregate, "first-2");
        seed(secondAggregate, "second-1");
        seed(secondAggregate, "second-2");

        List<OutboxMessage> published = Collections.synchronizedList(new ArrayList<>());
        Gate gate = new Gate();
        OutboxRelay blockedRelay = relayWith(new GatedPublisher(published, gate));
        OutboxRelay peerRelay = relayWith(new RecordingPublisher(published));

        Thread holder = new Thread(() -> blockedRelay.relay());
        holder.start();
        assertThat(gate.awaitEntered())
                .as("the first relay must reach the publish step before the peer starts")
                .isTrue();

        peerRelay.relay();

        gate.open();
        holder.join(30_000);
        assertThat(holder.isAlive()).as("the blocked relay must finish").isFalse();

        for (int round = 0; round < DRAIN_ROUNDS && pendingCount() > 0; round++) {
            peerRelay.relay();
        }

        assertThat(publishedFor(published, firstAggregate))
                .as("the events of one aggregate must reach the broker in creation order, "
                        + "also when two relay instances publish concurrently")
                .containsExactly("first-1", "first-2");
        assertThat(publishedFor(published, secondAggregate))
                .as("the events of one aggregate must reach the broker in creation order, "
                        + "also when two relay instances publish concurrently")
                .containsExactly("second-1", "second-2");
        assertThat(pendingCount()).as("every seeded event must leave the outbox").isZero();
    }

    private OutboxRelay relayWith(KafkaMessagePublisher publisher) {
        OutboxRelay relay = new OutboxRelay(outboxRepo, publisher, metrics, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", BATCH_SIZE);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
        return relay;
    }

    private List<String> publishedFor(List<OutboxMessage> published, UUID aggregateId) {
        List<String> payloads = new ArrayList<>();
        synchronized (published) {
            for (OutboxMessage message : published) {
                if (aggregateId.toString().equals(message.key())) {
                    payloads.add(String.valueOf(message.value()));
                }
            }
        }
        return payloads;
    }

    private long pendingCount() {
        return outboxRepo.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, 1000)).size();
    }

    private void seed(UUID aggregateId, String payload) {
        outboxRepo.saveAndFlush(OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("product")
                .aggregateId(aggregateId)
                .eventType("product.updated.v1")
                .topic("shop.product.events.v1")
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build());
    }

    private static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch opened = new CountDownLatch(1);

        boolean awaitEntered() throws InterruptedException {
            return entered.await(30, TimeUnit.SECONDS);
        }

        void open() {
            opened.countDown();
        }

        void awaitOpen() throws InterruptedException {
            opened.await(30, TimeUnit.SECONDS);
        }
    }

    private static final class GatedPublisher extends KafkaMessagePublisher {

        private final List<OutboxMessage> sink;
        private final Gate gate;

        GatedPublisher(List<OutboxMessage> sink, Gate gate) {
            super((KafkaTemplate<String, String>) null);
            this.sink = sink;
            this.gate = gate;
        }

        @Override
        public BatchOutcome publishBatch(List<OutboxMessage> records, long timeout, TimeUnit unit) {
            gate.entered.countDown();
            try {
                gate.awaitOpen();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return record(sink, records);
        }
    }

    private static final class RecordingPublisher extends KafkaMessagePublisher {

        private final List<OutboxMessage> sink;

        RecordingPublisher(List<OutboxMessage> sink) {
            super((KafkaTemplate<String, String>) null);
            this.sink = sink;
        }

        @Override
        public BatchOutcome publishBatch(List<OutboxMessage> records, long timeout, TimeUnit unit) {
            return record(sink, records);
        }
    }

    private static BatchOutcome record(List<OutboxMessage> sink, List<OutboxMessage> records) {
        synchronized (sink) {
            sink.addAll(records);
        }
        List<BatchOutcome.RecordOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            outcomes.add(new BatchOutcome.RecordOutcome(i, true, null));
        }
        return new BatchOutcome(records.size(), records.size(), true, outcomes);
    }
}
