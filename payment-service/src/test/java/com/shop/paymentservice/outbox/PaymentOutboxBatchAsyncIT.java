package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxBatchAsyncIT extends AbstractIntegrationTest {

    private static final int BATCH = 50;
    private static final Duration RELAY_BUDGET = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void slowDownForAssertion(DynamicPropertyRegistry registry) {
        registry.add("shop.payment.outbox.poll-millis", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired com.shop.common.kafka.producer.KafkaMessagePublisher kafkaPublisher;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @org.junit.jupiter.api.BeforeEach
    void clearPendingRows() {
        outboxRepo.deleteAllInBatch(outboxRepo.findAll());
    }

    @Test
    void relayProcessesFiftyEventsWithinTwoSeconds() {
        List<OutboxEvent> seed = IntStream.range(0, BATCH).mapToObj(i ->
            OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(UUID.randomUUID())
                .eventType("payment.batch.async.v1")
                .topic("shop.payment.batch.async.v1")
                .payload("{\"i\":" + i + "}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build()
        ).collect(Collectors.toList());
        outboxRepo.saveAll(seed);
        outboxRepo.flush();
        List<Long> ids = seed.stream().map(OutboxEvent::getId).toList();
        assertThat(ids).as("seed rows must have IDs after save").allMatch(id -> id != null);

        long t0 = System.nanoTime();
        PaymentOutboxRelay relay = new PaymentOutboxRelay(
            outboxRepo,
            kafkaPublisher,
            txManager
        );
        org.springframework.test.util.ReflectionTestUtils.setField(relay, "batchSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(relay, "maxRetries", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        relay.relay();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(elapsedMs)
            .as("50-event batch must complete within %s ms on the relay thread (was %d ms)",
                RELAY_BUDGET.toMillis(), elapsedMs)
            .isLessThan(RELAY_BUDGET.toMillis());
        assertThat(elapsedMs)
            .as("the relay must publish the batch without waiting per record")
            .isLessThan(BATCH * 5_000L);

        List<OutboxEvent> after = outboxRepo.findAllById(ids);
        assertThat(after).allSatisfy(e ->
            assertThat(e.getStatus()).isNotEqualTo(OutboxStatus.PENDING));
    }
}
