package com.shop.productservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * F-059 (P2) — purges SENT outbox rows older than the retention window.
 *
 * <p>product-service had no retention scheduler at all, so its
 * {@code outbox_events} table grew by one row per product / brand / category
 * mutation forever. Every other event-producing service in the fleet
 * (inventory, promotion, media, payment, shipping, order) purges SENT rows
 * after seven days; this service was the outlier and its repository did not
 * even have the delete method.</p>
 *
 * <p>Only SENT rows are purged. PENDING rows are the relay's backlog, FAILED
 * rows need a manual root cause before deletion, and DEAD rows are the
 * explicit "parked for an operator" state.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepository;

    @Scheduled(cron = "${product.outbox.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldSentEvents() {
        try {
            int deleted = outboxRepository.deleteByStatusAndSentAtBefore(
                    OutboxStatus.SENT, Instant.now().minus(7, ChronoUnit.DAYS));
            if (deleted > 0) {
                log.info("Purged {} SENT outbox events older than 7 days", deleted);
            }
        } catch (Exception ex) {
            log.error("Outbox retention purge failed - needs ops attention", ex);
        }
    }
}
