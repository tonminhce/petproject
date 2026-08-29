package com.shop.orderservice.service.impls;

import com.shop.orderservice.entity.OutboxStatus;
import com.shop.orderservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepository;

    @Value("${order.outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(cron = "${order.outbox.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldSentEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            int deleted = outboxRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
            if (deleted > 0) log.info("Purged {} SENT outbox events older than {} days", deleted, retentionDays);
        } catch (Exception ex) {
            log.error("Outbox purge failed", ex);
            // Spring's ScheduledAnnotationBeanPostProcessor also auto-logs ERROR;
            // this catch + custom message improves alert routing
        }
    }
}