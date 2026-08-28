package com.shop.inventoryservice.service;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Drains the {@code outbox_events} table and publishes each PENDING row to
 * Kafka through {@link KafkaMessagePublisher}.
 *
 * <p>Single-thread, ordered by id ASC. On a publish failure we save the row
 * (retry count + possibly FAILED) then {@code break} - later events of the
 * same aggregate must not overtake an earlier failed one. The failed row
 * stays PENDING and is retried on the next poll.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("${inventory.outbox.batch-size:100}")
    private int batchSize;

    @Value("${inventory.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval-ms:5000}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }
        log.info("Relaying {} outbox event(s)", pending.size());
        for (OutboxEvent event : pending) {
            try {
                kafkaPublisher.publish(
                    event.getTopic(),
                    event.getAggregateId().toString(),  // Kafka key = productId
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
                    log.error("Outbox event {} permanently failed after {} retries",
                        event.getEventId(), maxRetries, ex);
                } else {
                    log.warn("Outbox event {} retry {}/{}: {}",
                        event.getEventId(), event.getRetryCount(), maxRetries, ex.getMessage());
                }
                outboxRepo.save(event);
                break;  // STOP: keep ordering - retry later events next poll
            }
        }
    }
}
