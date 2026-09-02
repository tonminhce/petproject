package com.shop.paymentservice.outbox;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.core.constants.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drains {@code outbox_events} and publishes each PENDING row to Kafka.
 *
 * <p><b>C14</b> — rows are claimed one at a time via
 * {@link OutboxEventRepository#claimOnePending} (PESSIMISTIC_WRITE + SKIP
 * LOCKED) inside a per-event {@link TransactionTemplate} transaction (the F2
 * scheduler idiom used by {@code WebhookRetryScheduler} — self-invocation
 * would bypass {@code @Transactional}): the row lock spans the Kafka publish
 * and the status save, so a second relay instance can never claim and
 * double-publish the same row — it gets an empty claim and moves on.</p>
 *
 * <p><b>H44</b> — the relay no longer blocks on a per-event
 * {@code future.get(10s)} (which would let a single slow publish pin the
 * scheduler thread for ten seconds). Inside a single drain cycle the relay
 * claims up to {@code batchSize} rows, fans them out through
 * {@link KafkaMessagePublisher#publishBatch(List, long, java.util.concurrent.TimeUnit)}
 * which fires all sends in parallel and waits on a bounded latch, then
 * marks each row SENT (or retries on a recorded failure) — the relay's
 * wall-clock budget becomes roughly the slowest single publish, not the
 * sum. The wire contract (single-encoded JSON-as-String, traceparent
 * header) is preserved unchanged — the publisher's async path uses the
 * same {@code traceparentRecord} builder as the sync path.</p>
 */
@Component
@Slf4j
public class PaymentOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.payment.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.payment.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${shop.payment.outbox.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    public PaymentOutboxRelay(OutboxEventRepository outboxRepo,
                              KafkaMessagePublisher kafkaPublisher,
                              PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.payment.outbox.poll-millis:2000}", initialDelayString = "${shop.payment.outbox.poll-millis:2000}")
    public void relay() {
        // Phase 1 — claim up to batchSize rows in their own per-row transactions
        // (row lock + claim guarantee, one-by-one to keep ordering semantics).
        List<Long> claimedIds = new ArrayList<>();
        for (int drained = 0; drained < batchSize; drained++) {
            Long claimed = transactionTemplate.execute(tx -> {
                Optional<OutboxEvent> locked = outboxRepo.claimOnePending(OutboxStatus.PENDING);
                if (locked.isEmpty()) {
                    return null;  // nothing pending (or all locked by peers) — stop
                }
                // Mark SENDING so a stuck relay row is visible to recovery tooling
                // without holding the lock long enough to matter.
                locked.get().setStatus(OutboxStatus.SENDING);
                locked.get().setLastError(null);
                outboxRepo.save(locked.get());
                return locked.get().getId();
            });
            if (claimed == null) break;
            claimedIds.add(claimed);
        }
        if (claimedIds.isEmpty()) return;

        // Phase 2 — load the rows and fan them out async.
        List<OutboxEvent> rows = outboxRepo.findAllById(claimedIds);
        List<OutboxMessage> messages = new ArrayList<>(rows.size());
        for (OutboxEvent e : rows) {
            messages.add(new OutboxMessage(e.getTopic(), e.getAggregateId().toString(), e.getPayload()));
        }
        BatchOutcome outcome = kafkaPublisher.publishBatch(
            messages, publishTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.debug("Outbox batch sent={} success={} completed={}", outcome.sent(), outcome.success(), outcome.completed());

        // Phase 3 — mark each row SENT or record the retry/failure.
        for (OutboxEvent e : rows) {
            transactionTemplate.executeWithoutResult(tx -> markFinalState(e, outcome.completed()));
        }
    }

    private void markFinalState(OutboxEvent event, boolean allCompleted) {
        // Reload under the row's lock so a concurrent recovery sweep sees a
        // consistent final state.
        OutboxEvent e = outboxRepo.findById(event.getId()).orElse(null);
        if (e == null || e.getStatus() != OutboxStatus.SENDING) {
            return;  // recovery already moved this row; skip.
        }
        if (allCompleted) {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            e.setLastError(null);
        } else {
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError("publish batch timeout (some sends in flight at deadline)");
            if (e.getRetryCount() >= maxRetries) {
                e.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event {} permanently failed after timeout", e.getEventId());
            } else {
                log.warn("Outbox event {} retry {}/{} after batch timeout",
                    e.getEventId(), e.getRetryCount(), maxRetries);
            }
        }
        outboxRepo.save(e);
    }
}
