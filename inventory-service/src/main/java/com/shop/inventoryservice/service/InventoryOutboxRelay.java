package com.shop.inventoryservice.service;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Drains the {@code outbox_events} table and publishes each PENDING row to
 * Kafka through {@link KafkaMessagePublisher}.
 *
 * <p>Single-thread, ordered by id ASC. On a publish failure we save the row
 * (retry count + possibly FAILED) then {@code break} - later events of the
 * same aggregate must not overtake an earlier failed one. The failed row
 * stays PENDING and is retried on the next poll. NOTE: this is deliberately
 * stricter than product-service's {@code OutboxRelay} (which continues the
 * batch) because reservation ordering per product matters for stock math;
 * the cost — bounded head-of-line blocking for unrelated aggregates for at
 * most {@code max-retries} polls — is accepted and documented here.</p>
 *
 * <p>C14 — rows are claimed one at a time via
 * {@link OutboxEventRepository#claimOnePending} (PESSIMISTIC_WRITE + SKIP
 * LOCKED) inside a per-event {@link TransactionTemplate} transaction — the
 * row lock spans the Kafka publish and the status save, so a second relay
 * instance can never claim and double-publish the same row (the
 * review-flagged 2-instance double-publish race) — it gets an empty claim
 * and moves on. Ordering is preserved because the claim always takes the
 * lowest still-PENDING id.</p>
 */
@Component
@Slf4j
public class InventoryOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final InventoryMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${inventory.outbox.batch-size:100}")
    private int batchSize;

    @Value("${inventory.outbox.max-retries:10}")
    private int maxRetries;

    public InventoryOutboxRelay(OutboxEventRepository outboxRepo,
                                KafkaMessagePublisher kafkaPublisher,
                                InventoryMetrics metrics,
                                PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval-ms:5000}", initialDelayString = "${inventory.outbox.poll-interval-ms:5000}")
    public void relay() {
        Instant started = Instant.now();
        try {
            metrics.setPendingOutboxCount((int) outboxRepo.countByStatus(OutboxStatus.PENDING));
            for (int drained = 0; drained < batchSize; drained++) {
                Boolean claimed = transactionTemplate.execute(tx -> {
                    Optional<OutboxEvent> locked = outboxRepo.claimOnePending(OutboxStatus.PENDING);
                    if (locked.isEmpty()) {
                        return Boolean.FALSE; // nothing pending (or all locked by peers) — stop
                    }
                    OutboxEvent event = locked.get();
                    try {
                        kafkaPublisher.publish(
                            event.getTopic(),
                            event.getAggregateId().toString(),  // Kafka key = productId
                            event.getPayload());
                        event.setStatus(OutboxStatus.SENT);
                        event.setSentAt(Instant.now());
                        event.setLastError(null);
                    } catch (Exception ex) {
                        event.setRetryCount(event.getRetryCount() + 1);
                        event.setLastError(ex.getMessage());
                        if (event.getRetryCount() >= maxRetries) {
                            event.setStatus(OutboxStatus.FAILED);
                            log.error("Outbox event {} permanently failed after {} retries",
                                event.getEventId(), maxRetries, ex);
                        } else {
                            log.warn("Outbox event {} retry {}/{}: {}",
                                event.getEventId(), event.getRetryCount(), maxRetries, ex.getMessage());
                        }
                        outboxRepo.save(event);
                        return Boolean.FALSE;  // STOP: keep ordering - retry later events next poll
                    }
                    outboxRepo.save(event);
                    return Boolean.TRUE;
                });
                if (!Boolean.TRUE.equals(claimed)) break;
            }
        } finally {
            metrics.recordRelayDuration(Duration.between(started, Instant.now()));
        }
    }
}
