package com.shop.shippingservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * H-12: Purges SENT outbox rows older than retention window.
 * Without this scheduler, the shipping outbox table grows unbounded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShippingOutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepository;

    @Scheduled(cron = "${shop.shipping.outbox.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldSentEvents() {
        try {
            int deleted = outboxRepository.deleteByStatusAndSentAtBefore(
                OutboxStatus.SENT, Instant.now().minus(7, ChronoUnit.DAYS));
            if (deleted > 0) {
                log.info("Purged {} SENT shipping outbox events older than 7 days", deleted);
            }
        } catch (Exception ex) {
            log.error("Shipping outbox retention purge failed - needs ops attention", ex);
        }
    }
}
