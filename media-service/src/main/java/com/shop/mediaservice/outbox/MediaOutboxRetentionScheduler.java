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

/**
 * H-5 relay aging — nightly retention over the media outbox (ports
 * inventory-service's {@code OutboxRetentionScheduler} shape and EXTENDS it
 * with terminal aging, which media needs because it is the only relay that
 * replays FAILED):
 *
 * <ul>
 *   <li><strong>SENT &gt; retention window</strong> → purged (the table would
 *   otherwise grow one row per domain event forever; a successfully published
 *   event has no residual value).</li>
 *   <li><strong>FAILED &gt; terminal window</strong> → aged to {@link OutboxStatus#DEAD}
 *   (terminal) + WARN + {@code media_outbox_dead_total} meter. Media replays
 *   FAILED forever, so without a terminal state a permanently-broken row
 *   would retry until the end of time — DEAD is the ops signal that manual
 *   root-cause is overdue (runbook: inspect {@code last_error}, re-drive or
 *   delete by hand; DEAD rows are NEVER auto-purged).</li>
 * </ul>
 *
 * <p>Younger FAILED rows keep replaying — the relay only ever polls
 * PENDING + FAILED and never sees DEAD. Same failure posture as the inventory
 * original: any exception is logged (ERROR) and swallowed so the scheduler
 * never crashes the cycle.</p>
 */
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
            throw ex;
        }
    }
}
