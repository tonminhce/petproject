package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * D4 relay: polls due outbox rows in insertion order and publishes them
 * to Kafka, key = {@code aggregateId} (= mediaId, per-media partition
 * ordering). Scheduling shape ports rating-service's outbox relay:
 * fixed-delay poll, one batch per tick, first publish failure breaks the
 * loop — with per-media partition ordering the head-of-queue row is on the
 * same partition as everything behind it, so blocking preserves order.
 * No DLT: a failed row stays PENDING (up to {@code max-retries}, then
 * FAILED), and FAILED rows are REPLAYED on subsequent cycles (one attempt
 * per row per cycle, batch-capped, first-failure break — no hot loop), so
 * a broker outage past {@code max-retries} delays delivery instead of
 * killing it. Containment fleet posture.
 *
 * <p>H-5: FAILED is deliberately NOT terminal — but it is not forever either.
 * A parked row is stamped {@code failed_at} (cleared again on any later
 * success) and the nightly {@link MediaOutboxRetentionScheduler} ages
 * FAILED rows past the terminal window to DEAD, which this relay never
 * polls. Replay for younger FAILED rows continues unchanged.</p>
 *
 * <p>Type-agnostic by design: the row carries its eventType and FULL
 * snapshot payload; the relay publishes whatever the row carries and
 * consumers ack-skip unknown types.</p>
 *
 * <p>C14 — due rows (PENDING or replayable FAILED) are claimed one at a time
 * via {@link OutboxEventRepository#claimOneDue} (PESSIMISTIC_WRITE + SKIP
 * LOCKED) inside a per-event {@link TransactionTemplate} transaction: the row
 * lock spans the Kafka publish and the status save, so a second relay
 * instance can never claim and double-publish the same row — it gets an
 * empty claim and moves on. Ordering (id ASC across both due statuses,
 * head-of-line break on failure) is preserved because the claim always takes
 * the lowest still-due id.</p>
 */
@Component
@Slf4j
public class MediaOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.media.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.media.outbox.max-retries:10}")
    private int maxRetries;

    public MediaOutboxRelay(OutboxEventRepository outboxRepo,
                            KafkaMessagePublisher kafkaPublisher,
                            PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.media.outbox.poll-millis:2000}", initialDelayString = "${shop.media.outbox.poll-millis:2000}")
    public void relay() {
        List<OutboxStatus> dueStatuses = List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);
        for (int drained = 0; drained < batchSize; drained++) {
            Boolean claimed = transactionTemplate.execute(tx -> {
                Optional<OutboxEvent> locked = outboxRepo.claimOneDue(dueStatuses);
                if (locked.isEmpty()) {
                    return Boolean.FALSE; // nothing due (or all locked by peers) — stop
                }
                OutboxEvent event = locked.get();
                try {
                    kafkaPublisher.publish(event.getTopic(),
                        event.getAggregateId().toString(),
                        event.getPayload());
                    event.setStatus(OutboxStatus.SENT);
                    event.setSentAt(Instant.now());
                    event.setLastError(null);
                    event.setFailedAt(null); // H-5: a replayed FAILED row leaves the aging clock
                } catch (Exception ex) {
                    event.setRetryCount(event.getRetryCount() + 1);
                    event.setLastError(ex.getMessage());
                    if (event.getRetryCount() >= maxRetries) {
                        event.setStatus(OutboxStatus.FAILED);
                        // H-5: stamp the aging clock the row is parked with — the
                        // retention scheduler measures the terminal window against it
                        event.setFailedAt(Instant.now());
                        log.error("Media outbox event {} failed {} attempt(s) — parked as FAILED, "
                                + "will be replayed on a later cycle (aged to DEAD after the "
                                + "retention window)", event.getEventId(), event.getRetryCount(), ex);
                    } else {
                        log.warn("Media outbox event {} retry {}/{}", event.getEventId(), event.getRetryCount(), maxRetries, ex);
                    }
                    outboxRepo.save(event);
                    return Boolean.FALSE;  // head-of-line break — preserves per-media ordering
                }
                outboxRepo.save(event);
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(claimed)) break;
        }
    }
}
