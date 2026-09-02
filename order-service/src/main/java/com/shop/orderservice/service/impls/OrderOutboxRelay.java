package com.shop.orderservice.service.impls;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.core.constants.OutboxStatus;
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
import java.util.Optional;

/**
 * Drains {@code outbox_events} and publishes each PENDING row to Kafka. Breaks on
 * the first failure so events for the same aggregate stay ordered (deliberate
 * divergence from product-service's continue-on-error relay).
 *
 * <p>C14 — rows are claimed one at a time via
 * {@link OutboxEventRepository#claimOnePending} (PESSIMISTIC_WRITE + SKIP
 * LOCKED) inside a per-event {@link TransactionTemplate} transaction (the F2
 * scheduler idiom — self-invocation would bypass {@code @Transactional}): the
 * row lock spans the Kafka publish and the status save, so a second relay
 * instance can never claim and double-publish the same row — it gets an empty
 * claim and moves on. Ordering (id ASC, head-of-line break on failure) is
 * preserved because the claim always takes the lowest still-PENDING id.</p>
 *
 * <p>Metered via {@link OrderMetrics}: batch drain time is recorded in a
 * {@code finally} block, and the pending gauge is refreshed on every tick.</p>
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

    public OrderOutboxRelay(OutboxEventRepository outboxRepo,
                            KafkaMessagePublisher kafkaPublisher,
                            OrderMetrics metrics,
                            PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:5000}", initialDelayString = "${order.outbox.poll-interval-ms:5000}")
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
                            event.getAggregateId().toString(),  // Kafka key = orderId
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
        } finally {
            metrics.recordRelayDuration(Duration.between(started, Instant.now()));
        }
    }
}
