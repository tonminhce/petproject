package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import com.shop.common.kafka.producer.OutboxBatchOrdering;
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
 * Polls the due outbox rows - PENDING and the replayable FAILED ones - and
 * publishes them to Kafka, keyed by the media id so every event of one media
 * lands on one partition.
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
 *       advances and the row goes back to PENDING - or is parked as FAILED
 *       once the retry budget is spent.</li>
 * </ol>
 *
 * <p>Two properties of this service's outbox are kept:</p>
 * <ul>
 *   <li><b>FAILED is not terminal.</b> A parked row is stamped
 *       {@code failed_at} and replayed on a later cycle; the nightly
 *       {@link MediaOutboxRetentionScheduler} ages FAILED rows past the
 *       terminal window to DEAD, which this relay never polls.</li>
 *   <li><b>Per-media ordering.</b> The batch is published in claim order
 *       (ascending id), and a record that failed blocks the records behind
 *       it OF THE SAME MEDIA only - they are retried after the failed one
 *       succeeds, so a consumer never sees a later event of a media before
 *       an earlier one. Other media keep flowing.</li>
 * </ul>
 *
 * <p>The SENDING state has a recovery path: the stale sweep at the start of
 * every tick returns rows whose claim lease expired to PENDING, or parks
 * them as FAILED when the retry budget is already spent, so a crash between
 * the claim and the publish costs a retry, not the event.</p>
 */
@Component
@Slf4j
public class MediaOutboxRelay {

    private static final List<OutboxStatus> DUE_STATUSES = List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.media.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.media.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${shop.media.outbox.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    /**
     * How long a claimed (SENDING) row may stay unpublished before the
     * recovery sweep reclaims it. Must comfortably exceed the publish
     * timeout so a slow in-flight publish is never mistaken for a crash.
     */
    @Value("${shop.media.outbox.claim-lease-ms:60000}")
    private long claimLeaseMs;

    /** {@code last_error} is a varchar(1000) - the stored message is truncated to fit. */
    private static final int MAX_ERROR_LENGTH = 1000;

    public MediaOutboxRelay(OutboxEventRepository outboxRepo,
                            KafkaMessagePublisher kafkaPublisher,
                            PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.media.outbox.poll-millis:2000}", initialDelayString = "${shop.media.outbox.poll-millis:2000}")
    public void relay() {
        // Phase 0 - recover the rows a previous crash stranded in SENDING.
        recoverStrandedClaims();

        // Phase 1 - claim up to batchSize due rows, each in its own short
        // transaction. The row lock is taken and released here; no publish
        // happens while it is held.
        List<Long> claimedIds = new ArrayList<>();
        for (int drained = 0; drained < batchSize; drained++) {
            Long claimed = transactionTemplate.execute(tx -> {
                Optional<OutboxEvent> locked = outboxRepo.claimOneDue(DUE_STATUSES);
                if (locked.isEmpty()) {
                    return null;  // nothing due (or all locked by peers) - stop
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
        // MEDIA: they must be retried after it succeeds, so a consumer never
        // sees a later event of a media before an earlier one.
        boolean[] markable = OutboxBatchOrdering.markableAsSent(keys, delivered);

        // Phase 3 - mark each row by its own outcome.
        for (int i = 0; i < rows.size(); i++) {
            final OutboxEvent row = rows.get(i);
            final boolean sent = markable[i];
            final String error = errors.get(i);
            transactionTemplate.executeWithoutResult(tx -> markFinalState(row, sent, error));
        }
    }

    /**
     * Returns events stranded in the SENDING state to the claimable set.
     *
     * <p>A crash between the claim commit and the publish leaves the row in
     * SENDING, which the due-claim never picks up: it is neither PENDING nor
     * FAILED, and nothing else in the service touches it. The reclaim uses
     * the row's claim time against the claim lease. Rows whose retry budget
     * is already spent are parked as FAILED (with the aging clock restarted)
     * instead of being retried forever.</p>
     */
    private void recoverStrandedClaims() {
        Instant cutoff = Instant.now().minusMillis(claimLeaseMs);
        // The reclaim is a bulk (@Modifying) JPQL update - Hibernate requires
        // an active transaction for it, so the sweep runs inside its own
        // short transaction.
        Instant now = Instant.now();
        Integer reclaimed = transactionTemplate.execute(tx -> outboxRepo.reclaimStaleSending(
                OutboxStatus.SENDING, OutboxStatus.PENDING, OutboxStatus.FAILED,
                cutoff, maxRetries,
                "reclaimed after the claim lease expired - the relay died before publishing",
                now));
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
            e.setFailedAt(null);  // a replayed FAILED row leaves the aging clock
        } else {
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError(truncate(error));
            if (e.getRetryCount() >= maxRetries) {
                e.setStatus(OutboxStatus.FAILED);
                e.setFailedAt(Instant.now());
                log.error("Media outbox event {} failed {} attempt(s) - parked as FAILED, "
                        + "will be replayed on a later cycle (aged to DEAD after the "
                        + "retention window)", e.getEventId(), e.getRetryCount());
            } else {
                e.setStatus(OutboxStatus.PENDING);
                log.warn("Media outbox event {} retry {}/{}: {}",
                        e.getEventId(), e.getRetryCount(), maxRetries, e.getLastError());
            }
        }
        outboxRepo.save(e);
    }

    /**
     * The publish order must be the claim order.
     *
     * <p>{@code findAllById} returns the rows in whatever order the database
     * produced, which is NOT the id order. Two events of one media would
     * then reach the consumer in an arbitrary order. Sorting by id restores
     * the production order: the claim always takes the lowest due id, so
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
