package com.shop.promotionservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.promotionservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purges SENT outbox rows older than the retention window - without this the
 * table grows unbounded (one row per domain event forever). FAILED rows are
 * NOT purged: they need manual root-cause before deletion (ops runbook).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepository;

    @Scheduled(cron = "${promotion.outbox.retention-cron:0 0 3 * * *}")
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
