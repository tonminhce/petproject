package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

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
 * <p>Type-agnostic by design: the row carries its eventType and FULL
 * snapshot payload; the relay publishes whatever the row carries and
 * consumers ack-skip unknown types.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("${shop.media.outbox.batch-size:100}")
    private int batchSize;

    @Value("${shop.media.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${shop.media.outbox.poll-millis:2000}")
    public void relay() {
        List<OutboxEvent> due = outboxRepo.findByStatusInOrderByIdAsc(
            List.of(OutboxStatus.PENDING, OutboxStatus.FAILED), PageRequest.of(0, batchSize));
        if (due.isEmpty()) return;
        log.info("Relaying {} media outbox event(s)", due.size());
        for (OutboxEvent event : due) {
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
                    log.error("Media outbox event {} failed {} attempt(s) — parked as FAILED, "
                            + "will be replayed on a later cycle", event.getEventId(), event.getRetryCount(), ex);
                } else {
                    log.warn("Media outbox event {} retry {}/{}", event.getEventId(), event.getRetryCount(), maxRetries, ex);
                }
                outboxRepo.save(event);
                break;
            }
        }
    }
}
