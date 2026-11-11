package com.shop.orderservice.service.impls;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.kafka.producer.OutboxBatchOrdering;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.repository.OutboxEventRepository;
import com.shop.orderservice.service.OrderMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Drains the {@code outbox_events} table and publishes each PENDING row to
 * Kafka, keyed by the orderId so every event of one aggregate lands on
 * one partition.
 *
 * <p>The relay runs in three phases so the row lock is never held across a
 * Kafka publish:</p>
 * <ol>
 *   <li><b>claim</b> - a short transaction per row takes the row lock
 *       (PESSIMISTIC_WRITE + SKIP LOCKED), moves the row to SENDING, stamps
 *       the claim time and commits. The lock is released before any publish
 *       happens, so a second relay instance can claim a different row
 *       immediately instead of waiting behind the publish, and a publish
 *       timeout can no longer pin the scheduler thread.</li>
 *   <li><b>publish</b> - the whole batch is published outside any
 *       transaction through
 *       {@link KafkaMessagePublisher#publishBatch(List, long, java.util.concurrent.TimeUnit)},
 *       which fires the sends in parallel and waits on a bounded latch. The
 *       relay's wall-clock budget becomes roughly the slowest single
 *       publish instead of the sum of all of them.</li>
 *   <li><b>mark</b> - a short transaction per row records the outcome: SENT
 *       when the broker acked that record, otherwise the retry counter
 *       advances and the row returns to PENDING (or is parked as FAILED once
 *       the retry budget is spent).</li>
 * </ol>
 *
 * <p>Ordering per aggregate is preserved: the batch is published in claim
 * order (ascending id), and a record that failed blocks the records behind
 * it OF THE SAME AGGREGATE only - they are retried after the failed one
 * succeeds, so a consumer never sees a later event of an aggregate before an
 * earlier one. Other aggregates keep flowing.</p>
 *
 * <p>The SENDING state has a recovery path: the stale sweep at the start of
 * every tick returns rows whose claim lease expired to PENDING, so a crash
 * between the claim and the publish costs a retry, not the event.</p>
 */
@Component
@Slf4j
public class OrderOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final OrderMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${order.outbox.batch-size:100}")
    private int batchSize;

    @Value("${order.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${order.outbox.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    /**
     * How long a claimed (SENDING) row may stay unpublished before the
     * recovery sweep reclaims it. Must comfortably exceed the publish
     * timeout so a slow in-flight publish is never mistaken for a crash.
     */
    @Value("${order.outbox.claim-lease-ms:60000}")
    private long claimLeaseMs;

    /** {@code last_error} is a varchar(1000) - the stored message is truncated to fit. */
    private static final int MAX_ERROR_LENGTH = 1000;

    public OrderOutboxRelay(OutboxEventRepository outboxRepo,
                       KafkaMessagePublisher kafkaPublisher,
                       OrderMetrics metrics,
                       PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(
            fixedDelayString = "${order.outbox.poll-interval-ms:5000}",
            initialDelayString = "${order.outbox.poll-interval-ms:5000}")
    public void relay() {
        Instant started = Instant.now();
        try {
            metrics.setPendingOutboxCount((int) outboxRepo.countByStatus(OutboxStatus.PENDING));
            // Phase 0 - recover the rows a previous crash stranded in SENDING.
            recoverStrandedClaims();

            // Phase 1 - claim up to batchSize rows, each in its own short
            // transaction. The row lock is taken and released here; no
            // publish happens while it is held.
            List<Long> claimedIds = new ArrayList<>();
            for (int drained = 0; drained < batchSize; drained++) {
                Long claimed = transactionTemplate.execute(tx -> {
                    Optional<OutboxEvent> locked = outboxRepo.findFirstByStatusOrderByIdAsc(OutboxStatus.PENDING);
                    if (locked.isEmpty()) {
                        return null;  // nothing pending (or all locked by peers) - stop
                    }
                    OutboxEvent event = locked.get();
                    event.setStatus(OutboxStatus.SENDING);
                    event.setClaimedAt(Instant.now());
                    event.setLastError(null);
                    outboxRepo.save(event);
                    return event.getId();
                });
                if (claimed == null) {
                    break;
                }
                claimedIds.add(claimed);
            }
            if (claimedIds.isEmpty()) {
                return;
            }

            // Phase 2 - publish the batch outside any transaction, in claim order.
            List<OutboxEvent> rows = orderById(new ArrayList<>(outboxRepo.findAllById(claimedIds)));
            List<OutboxMessage> messages = new ArrayList<>(rows.size());
            List<String> keys = new ArrayList<>(rows.size());
            for (OutboxEvent e : rows) {
                String key = e.getAggregateId().toString();
                keys.add(key);
                messages.add(new OutboxMessage(e.getTopic(), key, e.getPayload()));
            }
            BatchOutcome outcome = kafkaPublisher.publishBatch(
                    messages, publishTimeoutMs, TimeUnit.MILLISECONDS);
            List<Boolean> delivered = new ArrayList<>(rows.size());
            List<String> errors = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                BatchOutcome.RecordOutcome record = (outcome != null && outcome.outcomes().size() == rows.size())
                        ? outcome.outcomes().get(i)
                        : null;
                delivered.add(record != null && record.success());
                errors.add(record == null ? "publish did not complete before the deadline" : record.error());
            }
            log.debug("Outbox batch sent={} success={} completed={}",
                    outcome == null ? 0 : outcome.sent(),
                    outcome == null ? 0 : outcome.success(),
                    outcome != null && outcome.completed());

            // A record that failed blocks the records behind it OF THE SAME
            // AGGREGATE: they must be retried after it succeeds, so a
            // consumer never sees a later event of an aggregate before an
            // earlier one.
            boolean[] markable = OutboxBatchOrdering.markableAsSent(keys, delivered);

            // Phase 3 - mark each row by its own outcome.
            for (int i = 0; i < rows.size(); i++) {
                final OutboxEvent row = rows.get(i);
                final boolean sent = markable[i];
                final String error = errors.get(i);
                transactionTemplate.executeWithoutResult(tx -> markFinalState(row, sent, error));
            }
        } finally {
            metrics.recordRelayDuration(Duration.between(started, Instant.now()));
        }
    }

    /**
     * Returns events stranded in the SENDING state to the claimable set.
     *
     * <p>A crash between the claim commit and the publish leaves the row in
     * SENDING, which no other code path touches: it is not PENDING, so the
     * claim never picks it up, and the event is lost silently. The reclaim
     * uses the row's claim time against the claim lease. Rows that have
     * already exhausted their retry budget are parked as FAILED for an
     * operator instead of being retried forever.</p>
     */
    private void recoverStrandedClaims() {
        Instant cutoff = Instant.now().minusMillis(claimLeaseMs);
        // The reclaim is a bulk (@Modifying) JPQL update - Hibernate requires
        // an active transaction for it, so the sweep runs inside its own
        // short transaction.
        Integer reclaimed = transactionTemplate.execute(tx -> outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                cutoff, maxRetries,
                "reclaimed after the claim lease expired - the relay died before publishing"));
        if (reclaimed != null && reclaimed > 0) {
            log.warn("Reclaimed {} outbox event(s) stranded in SENDING (claim lease {} ms expired)",
                    reclaimed, claimLeaseMs);
        }
    }

    private void markFinalState(OutboxEvent event, boolean published, String error) {
        // Re-read under the row's write lock so a concurrent recovery sweep
        // cannot move the row between the read and the write.
        OutboxEvent e = outboxRepo.findByIdForUpdate(event.getId()).orElse(null);
        if (e == null || e.getStatus() != OutboxStatus.SENDING) {
            return;  // the sweep already moved this row; skip.
        }
        if (published) {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            e.setLastError(null);
        } else {
            e.setStatus(OutboxStatus.PENDING);
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError(truncate(error));
            if (e.getRetryCount() >= maxRetries) {
                e.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event {} permanently failed after {} attempt(s): {}",
                        e.getEventId(), e.getRetryCount(), e.getLastError());
            } else {
                log.warn("Outbox event {} retry {}/{}: {}",
                        e.getEventId(), e.getRetryCount(), maxRetries, e.getLastError());
            }
        }
        outboxRepo.save(e);
    }

    /**
     * The publish order must be the claim order.
     *
     * <p>{@code findAllById} returns the rows in whatever order the database
     * produced, which is NOT the id order. Two events of one aggregate would
     * then reach the consumer in an arbitrary order. Sorting by id restores
     * the production order: the claim always takes the lowest pending id, so
     * ascending id IS the production order.</p>
     *
     * @param rows the claimed rows, in arbitrary order
     * @return the same rows, sorted by ascending id
     */
    static List<OutboxEvent> orderById(List<OutboxEvent> rows) {
        rows.sort(Comparator.comparing(OutboxEvent::getId));
        return rows;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown publish failure";
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
