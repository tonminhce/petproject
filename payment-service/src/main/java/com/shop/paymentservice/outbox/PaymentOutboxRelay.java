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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Drains {@code outbox_events} and publishes each PENDING row to Kafka.
 *
 * <p><b>Three phases, deliberately separated.</b> The row lock must NOT be
 * held across the Kafka publish: a publish can take up to the producer
 * timeout, and holding the lock for that long serialises every relay
 * instance on that row and pins the scheduler thread. So the relay does:</p>
 * <ol>
 *   <li><b>claim</b> — a short transaction per row takes the row lock
 *       (PESSIMISTIC_WRITE + SKIP LOCKED), moves the row to SENDING and
 *       commits. The lock is released before any publish happens, so a
 *       second relay instance can claim a DIFFERENT row immediately.</li>
 *   <li><b>publish</b> — the whole batch is published outside any
 *       transaction, through
 *       {@link KafkaMessagePublisher#publishBatch(List, long, java.util.concurrent.TimeUnit)},
 *       which fires the sends in parallel and waits on a bounded latch. The
 *       relay's wall-clock budget becomes roughly the slowest single
 *       publish, not the sum.</li>
 *   <li><b>mark</b> — a short transaction per row records the outcome:
 *       SENT when the broker acked that specific record, otherwise the retry
 *       counter advances and the row goes back to PENDING (or is parked as
 *       FAILED once the retry budget is spent).</li>
 * </ol>
 *
 * <p><b>The SENDING state has a recovery path.</b> Splitting the
 * claim from the publish opens a window: a process death between the claim
 * commit and the publish leaves the row in SENDING. No other code path
 * touches a SENDING row — it is not PENDING, so the claim never picks it up
 * — so the event was silently and permanently lost (a captured/refunded
 * payment event could disappear with no alarm and no retry). Every tick
 * therefore starts with {@link #recoverStrandedClaims()}: rows whose claim
 * lease has expired return to PENDING, or are parked as FAILED when their
 * retry budget is gone. The lease must comfortably exceed the publish
 * timeout so a slow in-flight publish is never mistaken for a crash.</p>
 *
 * <p><b>The publish order is the claim order.</b> Two events for the
 * same aggregate (one order, one payment) must reach the consumer in the
 * order they were produced, or the consumer sees a later state before an
 * earlier one. {@code findAllById} does not preserve the claim order, so the
 * claimed rows are sorted by id before the batch is built.</p>
 *
 * <p><b>Why the row is locked again when marking.</b> The mark phase
 * re-reads the row under a write lock and re-checks that it is still
 * SENDING. Without the lock, a recovery sweep could move the row to PENDING
 * between the read and the write, and the mark would then flip a row that
 * another relay instance may already have re-claimed and published — a
 * double publish that the row's own state said was impossible.</p>
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

    /**
     * How long a claimed (SENDING) row may stay unpublished before the
     * recovery sweep reclaims it. Must comfortably exceed the publish
     * timeout so a slow in-flight publish is never mistaken for a crash.
     */
    @Value("${shop.payment.outbox.claim-lease-ms:60000}")
    private long claimLeaseMs;

    /** {@code last_error} is a varchar(1000) — the stored message is truncated to fit. */
    private static final int MAX_ERROR_LENGTH = 1000;

    public PaymentOutboxRelay(OutboxEventRepository outboxRepo,
                              KafkaMessagePublisher kafkaPublisher,
                              PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.payment.outbox.poll-millis:2000}", initialDelayString = "${shop.payment.outbox.poll-millis:2000}")
    public void relay() {
        // Phase 0 — recover rows stranded in SENDING BEFORE claiming.
        // If this ran after the claim phase, a stranded row would have to wait
        // for the next tick; running it first means a tick that finds work
        // also recovers the rows the previous tick lost.
        recoverStrandedClaims();

        // Phase 1 — claim up to batchSize rows, each in its own short
        // transaction. The row lock is taken and released here; no publish
        // happens while it is held.
        List<Long> claimedIds = new ArrayList<>();
        for (int drained = 0; drained < batchSize; drained++) {
            Long claimed = transactionTemplate.execute(tx -> {
                Optional<OutboxEvent> locked = outboxRepo.claimOnePending(OutboxStatus.PENDING);
                if (locked.isEmpty()) {
                    return null;  // nothing pending (or all locked by peers) — stop
                }
                OutboxEvent event = locked.get();
                event.setStatus(OutboxStatus.SENDING);
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

        // Phase 2 — publish the batch outside any transaction.
        List<OutboxEvent> rows = orderById(new ArrayList<>(outboxRepo.findAllById(claimedIds)));
        List<OutboxMessage> messages = new ArrayList<>(rows.size());
        for (OutboxEvent e : rows) {
            messages.add(new OutboxMessage(e.getTopic(), e.getAggregateId().toString(), e.getPayload()));
        }
        BatchOutcome outcome = kafkaPublisher.publishBatch(
            messages, publishTimeoutMs, TimeUnit.MILLISECONDS);
        log.debug("Outbox batch sent={} success={} completed={}",
            outcome.sent(), outcome.success(), outcome.completed());

        // Phase 3 — mark each row by ITS OWN outcome. Marking the whole batch
        // on a single failure would re-publish the records the broker already
        // acked (legal under at-least-once, but needless duplicates that every
        // consumer then has to de-duplicate).
        List<BatchOutcome.RecordOutcome> outcomes = outcome.outcomes();
        for (int i = 0; i < rows.size(); i++) {
            BatchOutcome.RecordOutcome recordOutcome = (outcomes.size() == rows.size()) ? outcomes.get(i) : null;
            final boolean published = recordOutcome != null && recordOutcome.success();
            final String error = recordOutcome == null
                    ? "publish did not complete before the deadline"
                    : recordOutcome.error();
            final OutboxEvent row = rows.get(i);
            transactionTemplate.executeWithoutResult(tx -> markFinalState(row, published, error));
        }
    }

    /**
     * Returns events stranded in the SENDING state to the claimable set.
     * <p>A crash between the claim commit and the publish leaves the row in
     * SENDING, which no other code path touches: it is not PENDING, so the
     * claim never picks it up, and the event is lost silently. The reclaim
     * uses the row's updatedAt (set when it was claimed) against the claim
     * lease. Rows that have already exhausted their retry budget are parked
     * as FAILED for an operator instead of being retried forever.</p>
     */
    private void recoverStrandedClaims() {
        Instant cutoff = Instant.now().minusMillis(claimLeaseMs);
        // The reclaim is a bulk (@Modifying) JPQL update — Hibernate requires an
        // active transaction for it, so the sweep runs inside its own short
        // transaction. Running it outside one throws
        // InvalidDataAccessApiUsageException ("no EntityManager with actual
        // transaction available") on EVERY tick, which would take the whole
        // relay down instead of recovering the rows.
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
            return;  // recovery already moved this row; skip.
        }
        if (published) {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            e.setLastError(null);
        } else {
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

    private static String truncate(String message) {
        if (message == null) {
            return "unknown publish failure";
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * the publish order must be the claim order.
     *
     * <p>{@code findAllById} returns the rows in whatever order the database
     * produced them, which is NOT the id order. Two events for one aggregate
     * would then reach the consumer in an arbitrary order — a consumer could
     * see "refunded" before "captured" and end up with the wrong final state.
     * Sorting by id restores the production order: the claim always takes
     * the lowest pending id, so ascending id IS the production order.</p>
     *
     * @param rows the claimed rows, in arbitrary order
     * @return the same rows, sorted by ascending id
     */
    /**
     * The publish order must be the claim order.
     * <p>{@code findAllById} returns the rows in whatever order the database
     * produced them, which is NOT the id order. Two events for one aggregate
     * would then reach the consumer in an arbitrary order — a consumer could
     * see "refunded" before "captured" and end up with the wrong final state.
     * Sorting by id restores the production order: the claim always takes
     * the lowest pending id, so ascending id IS the production order.</p>
     *
     * @param rows the claimed rows, in arbitrary order
     * @return the same rows, sorted by ascending id
     */
    static List<OutboxEvent> orderById(List<OutboxEvent> rows) {
        rows.sort(Comparator.comparing(OutboxEvent::getId));
        return rows;
    }
}
