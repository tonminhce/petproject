package com.shop.productservice.service;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.repository.OutboxEventRepository;
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
 * Kafka through the shared {@link KafkaMessagePublisher}.
 *
 * <p>The relay runs on a fixed-delay schedule (see {@code product.outbox.poll-interval-ms})
 * and is intentionally NOT {@code @Transactional}: each
 * {@link OutboxEventRepository#save} call commits in its own transaction so a
 * mid-batch failure does not roll back earlier successes. {@link OutboxStatus}
 * is updated to {@code SENT} on success or {@code FAILED} once {@code retry_count}
 * reaches {@code max-retries}.</p>
 *
 * <p>Catches {@link Exception} (not just {@code KafkaPublishException}) so
 * transient infrastructure issues — e.g. timeout inside the Kafka producer —
 * do not abort the rest of the batch.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("${product.outbox.batch-size:100}")
    private int batchSize;

    @Value("${product.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${product.outbox.poll-interval-ms:5000}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }

        log.info("Relaying {} outbox event(s)", pending.size());
        for (OutboxEvent event : pending) {
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
            }
            outboxRepo.save(event);
        }
    }
}
