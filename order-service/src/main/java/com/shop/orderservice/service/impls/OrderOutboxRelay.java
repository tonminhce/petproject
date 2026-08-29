package com.shop.orderservice.service.impls;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.repository.OutboxEventRepository;
import com.shop.orderservice.service.OrderMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Drains {@code outbox_events} and publishes each PENDING row to Kafka. Breaks on
 * the first failure so events for the same aggregate stay ordered (deliberate
 * divergence from product-service's continue-on-error relay).
 *
 * <p>Metered via {@link OrderMetrics}: batch drain time is recorded in a
 * {@code finally} block, and the pending gauge is refreshed on every tick.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;
    private final OrderMetrics metrics;

    @Value("${order.outbox.batch-size:100}")
    private int batchSize;

    @Value("${order.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:5000}")
    public void relay() {
        Instant started = Instant.now();
        try {
            List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
                OutboxStatus.PENDING, PageRequest.of(0, batchSize));
            metrics.setPendingOutboxCount(pending.size());
            if (pending.isEmpty()) return;
            log.info("Relaying {} outbox event(s)", pending.size());
            for (OutboxEvent event : pending) {
                try {
                    kafkaPublisher.publish(event.getTopic(),
                        event.getAggregateId().toString(),  // Kafka key = orderId
                        event.getPayload());
                    event.setStatus(OutboxStatus.SENT);
                    event.setSentAt(Instant.now());
                    event.setLastError(null);
                    outboxRepo.save(event);
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
                    break;  // stop draining on failure — preserves per-aggregate ordering
                }
            }
        } finally {
            metrics.recordRelayDuration(Duration.between(started, Instant.now()));
        }
    }
}
