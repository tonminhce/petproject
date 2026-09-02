package com.shop.paymentservice.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shop.common.core.constants.OutboxStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * H11 — outbox retention purge. Without this scheduler, the
 * {@code outbox_events} table grows unbounded: every SENT row (after a
 * successful publish) and every DEAD row (after the relay gave up) lives
 * forever. This component bounds the table by deleting rows past a configurable
 * age window.
 *
 * <h3>Retention policy</h3>
 * <ul>
 *   <li><b>SENT</b> — keep {@code ${shop.payment.outbox.sent-retention:14d}}
 *       after {@code sent_at}. Consumers have already ACKed; nothing more to
 *       replay.</li>
 *   <li><b>DEAD</b> — keep {@code ${shop.payment.outbox.dead-retention:7d}}
 *       after {@code updated_at}. Ops has had a week to inspect
 *       {@code last_error} and triage — after that, the row is deleted and
 *       the operator log line stays as the only trace.</li>
 * </ul>
 * PENDING rows are NEVER purged — a stuck PENDING is a relay bug, not a
 * retention problem (reconciliation owns it).
 *
 * <p>Scheduled via {@code @Scheduled(fixedDelayString = "...")} with the same
 * timestamped property idiom as the rest of the fleet (see
 * {@code WebhookRetryScheduler}). Default cadence is 1 hour.</p>
 *
 * <p>Each delete runs in its own statement via {@code @Modifying} on the
 * repository — no transaction holds a row lock across the table; PG can run
 * the delete without blocking the relay. The repository returns the affected
 * row count, which is logged for observability.</p>
 */
@Component
@Slf4j
public class OutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepo;
    private final Clock clock;

    @Value("${shop.payment.outbox.sent-retention-seconds:1209600}")  // 14d default
    private long sentRetentionSeconds;

    @Value("${shop.payment.outbox.dead-retention-seconds:604800}")  // 7d default
    private long deadRetentionSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxRetentionScheduler(OutboxEventRepository outboxRepo) {
        this(outboxRepo, Clock.systemUTC());
    }

    /** Constructor for tests that want a fixed {@link Clock}. */
    public OutboxRetentionScheduler(OutboxEventRepository outboxRepo, Clock clock) {
        this.outboxRepo = outboxRepo;
        this.clock = clock;
    }

    @Scheduled(
        fixedDelayString = "${shop.payment.outbox.retention-poll-ms:3600000}",
        initialDelayString = "${shop.payment.outbox.retention-initial-delay-ms:60000}"
    )
    public void purge() {
        Instant now = Instant.now(clock);

        int deletedSent = outboxRepo.deleteByStatusAndSentAtBefore(
            OutboxStatus.SENT,
            now.minus(Duration.ofSeconds(sentRetentionSeconds))
        );
        if (deletedSent > 0) {
            log.info("Outbox retention purged sent={} (cutoff_age_seconds={})",
                deletedSent, sentRetentionSeconds);
        }

        int deletedDead = outboxRepo.deleteByStatusAndUpdatedAtBefore(
            OutboxStatus.DEAD,
            now.minus(Duration.ofSeconds(deadRetentionSeconds))
        );
        if (deletedDead > 0) {
            log.info("Outbox retention purged dead={} (cutoff_age_seconds={})",
                deletedDead, deadRetentionSeconds);
        }
    }
}
