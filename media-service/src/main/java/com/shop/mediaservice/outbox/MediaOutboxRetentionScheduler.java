package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.mediaservice.metrics.MediaMetrics;
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
public class MediaOutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepository;
    private final MediaMetrics mediaMetrics;

    @Value("${shop.media.outbox.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${shop.media.outbox.retention-cron:0 0 3 * * *}")
    @Transactional
    public void retain() {
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

            int purgedSent = outboxRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
            if (purgedSent > 0) {
                log.info("Purged {} SENT media outbox events older than {} day(s)", purgedSent, retentionDays);
            }

            int agedDead = outboxRepository.ageDeadFailedBefore(cutoff);
            if (agedDead > 0) {
                log.warn("Aged {} FAILED media outbox event(s) past {} day(s) to terminal DEAD — "
                        + "manual root-cause required (runbook), replay stops", agedDead, retentionDays);
                mediaMetrics.recordOutboxDead(agedDead);
            }
        } catch (Exception ex) {
            log.error("Media outbox retention failed - needs ops attention", ex);
        }
    }
}
