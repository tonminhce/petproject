package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H11 — outbox table grows unbounded: there is no retention purge, so every
 * SENT/DEAD row accumulates. This scheduler:
 * <ul>
 *   <li>deletes SENT events whose {@code sentAt} is older than the configured
 *       SENT retention (default 14d)</li>
 *   <li>deletes DEAD events whose {@code updatedAt} is older than the
 *       configured DEAD aging window (default 7d)</li>
 * </ul>
 * PENDING rows are NEVER purged (a stuck PENDING is a relay bug, not a
 * retention problem — the reconciliation scheduler owns it).
 */
@ExtendWith(MockitoExtension.class)
class OutboxRetentionSchedulerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock OutboxEventRepository outboxRepo;

    OutboxRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxRetentionScheduler(outboxRepo, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(scheduler, "sentRetentionSeconds", 14L * 24 * 3600);
        ReflectionTestUtils.setField(scheduler, "deadRetentionSeconds", 7L * 24 * 3600);
    }

    @Test
    void purgeDeletesSentEventsOlderThan14Days() {
        when(outboxRepo.deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), any(Instant.class)))
            .thenReturn(3);

        scheduler.purge();

        Instant expectedCutoff = FIXED_NOW.minusSeconds(14L * 24 * 3600);
        verify(outboxRepo).deleteByStatusAndSentAtBefore(OutboxStatus.SENT, expectedCutoff);
    }

    @Test
    void purgeDeletesDeadEventsOlderThan7Days() {
        when(outboxRepo.deleteByStatusAndUpdatedAtBefore(eq(OutboxStatus.DEAD), any(Instant.class)))
            .thenReturn(2);

        scheduler.purge();

        Instant expectedCutoff = FIXED_NOW.minusSeconds(7L * 24 * 3600);
        verify(outboxRepo).deleteByStatusAndUpdatedAtBefore(OutboxStatus.DEAD, expectedCutoff);
    }

    @Test
    void purgeNeverTouchesPendingRows() {
        // The scheduler only calls delete* on SENT + DEAD — verify no PENDING call.
        scheduler.purge();

        verify(outboxRepo, times(0)).deleteByStatusAndSentAtBefore(eq(OutboxStatus.PENDING), any(Instant.class));
    }
}
