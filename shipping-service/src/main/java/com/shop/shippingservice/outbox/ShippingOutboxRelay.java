package com.shop.shippingservice.outbox;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.core.constants.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShippingOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("${shop.shipping.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.shipping.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${shop.shipping.outbox.poll-millis:2000}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) return;
        log.info("Relaying {} outbox event(s)", pending.size());
        for (OutboxEvent event : pending) {
            try {
                kafkaPublisher.publish(event.getTopic(),
                    event.getAggregateId().toString(),
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
                break;
            }
        }
    }
}
