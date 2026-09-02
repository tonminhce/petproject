package com.shop.productservice.service;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.repository.OutboxEventRepository;
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
 * Kafka through the shared {@link KafkaMessagePublisher}.
 *
 * <p>C14 — rows are claimed one at a time via
 * {@link OutboxEventRepository#claimOnePending} (PESSIMISTIC_WRITE + SKIP
 * LOCKED) inside a per-event {@link TransactionTemplate} transaction (the F2
 * scheduler idiom — self-invocation would bypass {@code @Transactional}): the
 * row lock spans the Kafka publish and the status save, so a second relay
 * instance can never claim and double-publish the same row — it gets an empty
 * claim and moves on. Ordering (id ASC) is preserved because the claim always
 * takes the lowest still-PENDING id. Per-event transactions keep the relay's
 * original resilience: a mid-batch failure does not roll back earlier
 * successes, and this relay deliberately CONTINUES the batch on a publish
 * failure (retry counter bumped, row saved) unlike the stricter
 * order/promotion/inventory relays that break.</p>
 *
 * <p>Catches {@link Exception} (not just {@code KafkaPublishException}) so
 * transient infrastructure issues — e.g. timeout inside the Kafka producer —
 * do not abort the rest of the batch.</p>
 */
@Component
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final ProductMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${product.outbox.batch-size:100}")
    private int batchSize;

    @Value("${product.outbox.max-retries:10}")
    private int maxRetries;

    public OutboxRelay(OutboxEventRepository outboxRepo,
                       KafkaMessagePublisher kafkaPublisher,
                       ProductMetrics metrics,
                       PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${product.outbox.poll-interval-ms:5000}", initialDelayString = "${product.outbox.poll-interval-ms:5000}")
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
                        kafkaPublisher.publish(event.getTopic(),
                            String.valueOf(event.getAggregateId()),
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
                        // Continue-on-error relay (fleet divergence note above): save the
                        // retry state and keep draining — later aggregates must not starve.
                        outboxRepo.save(event);
                        return Boolean.TRUE;
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
