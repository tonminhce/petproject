package com.shop.ratingservice.outbox;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.core.constants.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * Drains {@code outbox_events} and publishes each PENDING row to Kafka.
 * Breaks on the first failure so events for the same aggregate stay ordered.
 *
 * <p>C14 — rows are claimed one at a time via
 * {@link OutboxEventRepository#claimOnePending} (PESSIMISTIC_WRITE + SKIP
 * LOCKED) inside a per-event {@link TransactionTemplate} transaction: the row
 * lock spans the Kafka publish and the status save, so a second relay
 * instance can never claim and double-publish the same row (the review-flagged
 * 2-instance double-publish race) — it gets an empty claim and moves on.
 * Ordering (id ASC, head-of-line break on failure) is preserved because the
 * claim always takes the lowest still-PENDING id.</p>
 */
@Component
@Slf4j
public class RatingOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${shop.rating.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.rating.outbox.max-retries:10}")
    private int maxRetries;

    public RatingOutboxRelay(OutboxEventRepository outboxRepo,
                             KafkaMessagePublisher kafkaPublisher,
                             PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${shop.rating.outbox.poll-millis:2000}", initialDelayString = "${shop.rating.outbox.poll-millis:2000}")
    public void relay() {
        for (int drained = 0; drained < batchSize; drained++) {
            Boolean claimed = transactionTemplate.execute(tx -> {
                Optional<OutboxEvent> locked = outboxRepo.claimOnePending(OutboxStatus.PENDING);
                if (locked.isEmpty()) {
                    return Boolean.FALSE; // nothing pending (or all locked by peers) — stop
                }
                OutboxEvent event = locked.get();
                try {
                    kafkaPublisher.publish(event.getTopic(),
                        event.getAggregateId().toString(),
                        event.getPayload());
                    event.setStatus(OutboxStatus.SENT);
                    event.setSentAt(Instant.now());
                    event.setLastError(null);
                } catch (Exception ex) {
                    event.setRetryCount(event.getRetryCount() + 1);
                    event.setLastError(ex.getMessage());
                    if (event.getRetryCount() >= maxRetries) {
                        event.setStatus(OutboxStatus.FAILED);
                        log.error("Outbox event {} permanently failed", event.getEventId(), ex);
                    } else {
                        log.warn("Outbox event {} retry {}/{}", event.getEventId(), event.getRetryCount(), maxRetries, ex);
                    }
                    outboxRepo.save(event);
                    return Boolean.FALSE;  // head-of-line break — preserves per-aggregate ordering
                }
                outboxRepo.save(event);
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(claimed)) break;
        }
    }
}
