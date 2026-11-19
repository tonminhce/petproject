package com.shop.paymentservice.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.shop.common.core.constants.OutboxStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class OutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepo;
    private final Clock clock;

    @Value("${shop.payment.outbox.sent-retention-seconds:1209600}")
    private long sentRetentionSeconds;

    @Value("${shop.payment.outbox.dead-retention-seconds:604800}")
    private long deadRetentionSeconds;

    @Autowired
    public OutboxRetentionScheduler(OutboxEventRepository outboxRepo) {
        this(outboxRepo, Clock.systemUTC());
    }

    public OutboxRetentionScheduler(OutboxEventRepository outboxRepo, Clock clock) {
        this.outboxRepo = outboxRepo;
        this.clock = clock;
    }

    @Scheduled(
        fixedDelayString = "${shop.payment.outbox.retention-poll-ms:3600000}",
        initialDelayString = "${shop.payment.outbox.retention-initial-delay-ms:60000}"
    )
    @Transactional
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
