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

/**
 * H44 — outbox relay perf: the previous per-event
 * {@code kafkaPublisher.publish(...)} blocked on a per-event future.get(10s)
 * — 50 events could take 500s of wall time. The fix is to fan the batch out
 * async and wait on a bounded latch, so the 50 events travel in parallel and
 * the relay's wall-clock budget becomes roughly the slowest single publish,
 * not the sum.
 *
 * <p>This IT pins the budget at {@code <= 2s} of clock skew after the relay
 * finishes dispatching — well above the natural round-trip on a co-located
 * Kafka, but well below the synchronous path's worst case. A regression
 * (someone removing the async latch and falling back to sync future.get)
 * trips the budget immediately. The DB-side assertion (rows moved out of
 * PENDING) is the cheap, deterministic proof; the broker side is covered
 * by the existing fleet consumer ITs.</p>
 */
class PaymentOutboxBatchAsyncIT extends AbstractIntegrationTest {

    private static final int BATCH = 50;
    private static final Duration RELAY_BUDGET = Duration.ofSeconds(2);

    @DynamicPropertySource
    static void slowDownForAssertion(DynamicPropertyRegistry registry) {
        // park the production relay idle; we call the new async batch path by hand.
        registry.add("shop.payment.outbox.poll-millis", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired com.shop.common.kafka.producer.KafkaMessagePublisher kafkaPublisher;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @org.junit.jupiter.api.BeforeEach
    void clearPendingRows() {
        // leftover PENDING rows from sibling tests would win the MIN(id) claim race.
        outboxRepo.deleteAllInBatch(outboxRepo.findAll());
    }

    @Test
    void relayProcessesFiftyEventsWithinTwoSeconds() {
        // Seed 50 PENDING rows
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
        // Drive the new async batch path inline (not the @Scheduled relay) so the test
        // pins the perf claim, not the scheduler timing. The Spring bean has @Value-
        // injected batch sizing; we mirror those defaults manually so the test owns
        // the same configuration the production relay runs with.
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

        // 1. Wall-clock budget: 50 events must finish within 2s on the relay thread.
        //    Async-fanout + bounded latch makes the relay thread's wait bounded by
        //    the slowest single publish, not the sum — sync future.get per event
        //    would push the budget past 10s on any single slow event.
        assertThat(elapsedMs)
            .as("50-event batch must complete within %s ms on the relay thread (was %d ms)",
                RELAY_BUDGET.toMillis(), elapsedMs)
            .isLessThan(RELAY_BUDGET.toMillis());

        // 2. DB side: every row moved out of PENDING.
        List<OutboxEvent> after = outboxRepo.findAllById(ids);
        assertThat(after).allSatisfy(e ->
            assertThat(e.getStatus()).isNotEqualTo(OutboxStatus.PENDING));
    }
}
