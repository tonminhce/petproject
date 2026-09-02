package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.mediaservice.metrics.MediaMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H-5 retention unit proofs (inventory OutboxRetentionScheduler shape,
 * extended with terminal aging): ONE cutoff drives both passes — SENT rows
 * purged by sent_at, FAILED rows aged to DEAD by failed_at — and the dead
 * count feeds the {@code media_outbox_dead_total} meter. A repository failure
 * is logged and swallowed (the scheduler never crashes the cycle).
 */
@ExtendWith(MockitoExtension.class)
class MediaOutboxRetentionSchedulerTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    private SimpleMeterRegistry registry;
    private MediaOutboxRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        scheduler = new MediaOutboxRetentionScheduler(outboxRepository, new MediaMetrics(registry));
        ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
    }

    private double deadMeter() {
        var counter = registry.find("media_outbox_dead_total").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("both passes share the same 7-day cutoff; dead count hits the meter")
    void retain_usesTheRetentionCutoffForBothPasses() {
        Instant before = Instant.now();
        when(outboxRepository.deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), any(Instant.class)))
                .thenReturn(2);
        when(outboxRepository.ageDeadFailedBefore(any(Instant.class))).thenReturn(3);

        scheduler.retain();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> sentCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), sentCutoff.capture());
        ArgumentCaptor<Instant> deadCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).ageDeadFailedBefore(deadCutoff.capture());
        // both passes measure the SAME window: cutoff ≈ now − 7 days
        assertThat(sentCutoff.getValue()).isEqualTo(deadCutoff.getValue());
        assertThat(sentCutoff.getValue())
                .isAfterOrEqualTo(before.minus(7, java.time.temporal.ChronoUnit.DAYS).minusSeconds(60))
                .isBeforeOrEqualTo(after.minus(7, java.time.temporal.ChronoUnit.DAYS));
        assertThat(deadMeter()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("nothing to do → no meter increment, no error")
    void retain_nothingToDo_isQuiet() {
        when(outboxRepository.deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), any(Instant.class)))
                .thenReturn(0);
        when(outboxRepository.ageDeadFailedBefore(any(Instant.class))).thenReturn(0);

        scheduler.retain();

        assertThat(deadMeter()).isZero();
    }

    @Test
    @DisplayName("repository failure → logged and swallowed, the scheduler never throws")
    void retain_repositoryFailure_neverThrows() {
        when(outboxRepository.deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> scheduler.retain()).doesNotThrowAnyException();

        // the SENT pass failed before the DEAD pass ran — no partial aging, no meter
        verify(outboxRepository, never()).ageDeadFailedBefore(any(Instant.class));
        assertThat(deadMeter()).isZero();
    }
}
