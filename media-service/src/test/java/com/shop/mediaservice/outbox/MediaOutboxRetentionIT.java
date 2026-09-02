package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-5 relay aging against the real stack (real Postgres, real Liquibase
 * changelog-003 with {@code failed_at}, real Kafka for the replay): the
 * nightly scheduler purges SENT rows past the retention window and ages
 * FAILED rows past the terminal window to DEAD (+ meter + WARN), while YOUNG
 * FAILED rows still REPLAY through the relay (which never polls DEAD). The
 * retention cron is pinned to a never-fire instant — only explicit
 * {@code retain()} / {@code relay()} calls run.
 */
class MediaOutboxRetentionIT extends AbstractMediaIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private MediaOutboxRetentionScheduler scheduler;

    @Autowired
    private MediaOutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** Scheduler + relay must not race the manual calls in tests. */
    @DynamicPropertySource
    static void schedulingProps(DynamicPropertyRegistry registry) {
        registry.add("shop.media.outbox.poll-millis", () -> "3600000");
        registry.add("shop.media.outbox.retention-cron", () -> "0 0 0 30 2 *");
    }

    private static final UUID MEDIA_ID = UUID.fromString("f3000000-0000-0000-0000-000000000001");

    private double deadMeterBefore;

    @BeforeEach
    void resetState() {
        outboxRepository.deleteAllInBatch();
        deadMeterBefore = deadMeter(meterRegistry);
    }

    private OutboxEvent row(OutboxStatus status, Instant sentAt, Instant failedAt, int retryCount) {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("media")
                .aggregateId(MEDIA_ID)
                .eventType("MediaCreated")
                .topic("media.lifecycle.v1")
                .payload("{\"eventType\":\"MediaCreated\",\"mediaId\":\"" + MEDIA_ID + "\"}")
                .status(status)
                .retryCount(retryCount)
                .sentAt(sentAt)
                .failedAt(failedAt)
                .build();
    }

    private double deadMeter(io.micrometer.core.instrument.MeterRegistry registry) {
        var counter = registry.find("media_outbox_dead_total").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("FAILED > 7d → terminal DEAD + meter; young FAILED survives the scheduler and REPLAYS")
    void retention_agesOldFailedToDead_youngFailedStillReplays() {
        OutboxEvent oldFailed = outboxRepository.save(
                row(OutboxStatus.FAILED, null, Instant.now().minusSeconds(8L * 24 * 3600), 10));
        OutboxEvent youngFailed = outboxRepository.save(
                row(OutboxStatus.FAILED, null, Instant.now().minusSeconds(1L * 24 * 3600), 3));

        scheduler.retain();

        OutboxEvent aged = outboxRepository.findById(oldFailed.getId()).orElseThrow();
        assertThat(aged.getStatus()).as("old FAILED aged to terminal DEAD").isEqualTo(OutboxStatus.DEAD);
        assertThat(aged.getFailedAt()).as("aging keeps the clock the row was parked with").isNotNull();
        OutboxEvent kept = outboxRepository.findById(youngFailed.getId()).orElseThrow();
        assertThat(kept.getStatus()).as("young FAILED keeps replaying").isEqualTo(OutboxStatus.FAILED);
        assertThat(deadMeter(meterRegistry)).as("media_outbox_dead_total counts the aged rows")
                .isEqualTo(deadMeterBefore + 1.0);

        // the relay never polls DEAD — the aged row stays put while the young one publishes
        relay.relay();

        OutboxEvent replayed = outboxRepository.findById(youngFailed.getId()).orElseThrow();
        assertThat(replayed.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(replayed.getSentAt()).isNotNull();
        assertThat(replayed.getFailedAt()).as("a replayed row leaves the aging clock").isNull();
        assertThat(outboxRepository.findById(oldFailed.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    @DisplayName("SENT > 7d purged; young SENT survives")
    void retention_purgesOldSentKeepsYoungSent() {
        OutboxEvent oldSent = outboxRepository.save(
                row(OutboxStatus.SENT, Instant.now().minusSeconds(8L * 24 * 3600), null, 0));
        OutboxEvent youngSent = outboxRepository.save(
                row(OutboxStatus.SENT, Instant.now().minusSeconds(1L * 24 * 3600), null, 0));

        scheduler.retain();

        assertThat(outboxRepository.findById(oldSent.getId())).as("old SENT purged").isEmpty();
        assertThat(outboxRepository.findById(youngSent.getId())).as("young SENT kept").isPresent();
    }

    @Test
    @DisplayName("DEAD rows are NEVER auto-purged — terminal, they wait for the ops runbook")
    void retention_deadRowsAreNeverAutoPurged() {
        OutboxEvent dead = outboxRepository.save(
                row(OutboxStatus.DEAD, null, Instant.now().minusSeconds(30L * 24 * 3600), 10));

        scheduler.retain();

        assertThat(outboxRepository.findById(dead.getId())).isPresent();
    }
}
