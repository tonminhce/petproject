package com.shop.notificationservice.service;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C12/C17 — the delivery state machine lives in the writer: status
 * transitions are the ONLY way a row reaches SENT (after a provider ack)
 * or a FAILED_* state, and every transition is guarded so a bug elsewhere
 * can never resurrect a settled row. SENT is only reachable from SENDING —
 * never from PENDING — which is exactly the C12 fix.
 */
@ExtendWith(MockitoExtension.class)
class NotificationWriterTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Mock NotificationRepository repository;

    private MeterRegistry meterRegistry;
    private NotificationWriter writer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        writer = new NotificationWriter(repository, meterRegistry);
    }

    private Notification row(NotificationStatus status) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .status(status)
                .build();
    }

    // ---- insert -------------------------------------------------------

    @Test
    void insert_flushesAndReturnsSavedRow() {
        Notification notification = Notification.builder().eventId(UUID.randomUUID()).build();
        when(repository.saveAndFlush(notification)).thenReturn(notification);

        Notification saved = writer.insert(notification);

        assertThat(saved).isSameAs(notification);
    }

    @Test
    void insert_duplicateRace_translatesToDataIntegrityViolation() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_event_id"));

        assertThatThrownBy(() -> writer.insert(Notification.builder().build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- claim (markSending) ------------------------------------------

    @Test
    void markSending_fromPending_claimsRowWithHeartbeat() {
        Notification n = row(NotificationStatus.PENDING);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));
        Instant heartbeat = NOW.plusSeconds(900);

        boolean claimed = writer.markSending(n.getId(), heartbeat, NOW);

        assertThat(claimed).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(n.getNextRetryAt()).isEqualTo(heartbeat);
        verify(repository).save(n);
    }

    @Test
    void markSending_fromRetryable_claimsRow() {
        Notification n = row(NotificationStatus.FAILED_RETRYABLE);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        boolean claimed = writer.markSending(n.getId(), NOW.plusSeconds(900), NOW);

        assertThat(claimed).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
    }

    @Test
    void markSending_fromStaleSending_reclaimsRow() {
        Notification n = row(NotificationStatus.SENDING);
        n.setNextRetryAt(NOW.minusSeconds(1)); // heartbeat elapsed
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        boolean claimed = writer.markSending(n.getId(), NOW.plusSeconds(900), NOW);

        assertThat(claimed).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(n.getNextRetryAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void markSending_fromFreshSending_rejected() {
        Notification n = row(NotificationStatus.SENDING);
        n.setNextRetryAt(NOW.plusSeconds(900)); // another instance's live heartbeat
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        boolean claimed = writer.markSending(n.getId(), NOW.plusSeconds(1800), NOW);

        assertThat(claimed).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(n.getNextRetryAt()).isEqualTo(NOW.plusSeconds(900));
        verify(repository, never()).save(any());
    }

    @Test
    void markSending_fromSettledStates_rejected() {
        for (NotificationStatus settled : new NotificationStatus[] {
                NotificationStatus.SENT, NotificationStatus.SKIPPED,
                NotificationStatus.FAILED_PERMANENT}) {
            Notification n = row(settled);
            when(repository.findById(n.getId())).thenReturn(Optional.of(n));

            boolean claimed = writer.markSending(n.getId(), NOW.plusSeconds(900), NOW);

            assertThat(claimed).as("status %s must not be claimable", settled).isFalse();
            assertThat(n.getStatus()).isEqualTo(settled);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void markSending_unknownId_returnsFalse() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThat(writer.markSending(UUID.randomUUID(), NOW.plusSeconds(900), NOW)).isFalse();
    }

    // ---- settle (markSent) --------------------------------------------

    @Test
    void markSent_fromSending_writesSentAndClearsRetryFields() {
        Notification n = row(NotificationStatus.SENDING);
        n.setNextRetryAt(NOW.plusSeconds(900));
        n.setLastError("previous failure");
        n.setRetryCount(2);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        boolean settled = writer.markSent(n.getId());

        assertThat(settled).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getNextRetryAt()).isNull();
        assertThat(n.getLastError()).isNull();
        verify(repository).save(n);
    }

    @Test
    void markSent_fromPending_rejected_c12Guard() {
        // C12 core guard: SENT must never be written before the send window opened.
        Notification n = row(NotificationStatus.PENDING);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThat(writer.markSent(n.getId())).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        verify(repository, never()).save(any());
    }

    @Test
    void markSent_unknownId_returnsFalse() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThat(writer.markSent(UUID.randomUUID())).isFalse();
    }

    // ---- settle (markRetryable) ---------------------------------------

    @Test
    void markRetryable_recordsAttemptBackoffAndError() {
        Notification n = row(NotificationStatus.SENDING);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));
        Instant nextRetry = NOW.plusSeconds(300);

        boolean settled = writer.markRetryable(n.getId(), 1, nextRetry, "smtp down");

        assertThat(settled).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED_RETRYABLE);
        assertThat(n.getRetryCount()).isEqualTo(1);
        assertThat(n.getNextRetryAt()).isEqualTo(nextRetry);
        assertThat(n.getLastError()).isEqualTo("smtp down");
        verify(repository).save(n);
    }

    @Test
    void markRetryable_truncatesErrorTo1024Chars() {
        Notification n = row(NotificationStatus.SENDING);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        writer.markRetryable(n.getId(), 1, NOW, "x".repeat(2000));

        assertThat(n.getLastError()).hasSize(1024);
    }

    @Test
    void markRetryable_fromSent_rejected() {
        Notification n = row(NotificationStatus.SENT);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThat(writer.markRetryable(n.getId(), 1, NOW, "boom")).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(repository, never()).save(any());
    }

    // ---- settle (markPermanent) ---------------------------------------

    @Test
    void markPermanent_recordsStatusAndErrorAndIncrementsCounter() {
        Notification n = row(NotificationStatus.SENDING);
        n.setRetryCount(6);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        boolean settled = writer.markPermanent(n.getId(), "invalid recipient");

        assertThat(settled).isTrue();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED_PERMANENT);
        assertThat(n.getLastError()).isEqualTo("invalid recipient");
        Double count = meterRegistry.find("notification_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(1.0);
        verify(repository).save(n);
    }

    @Test
    void markPermanent_fromPending_rejected() {
        Notification n = row(NotificationStatus.PENDING);
        when(repository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThat(writer.markPermanent(n.getId(), "boom")).isFalse();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        Double count = meterRegistry.find("notification_failed_permanent_total").counter().count();
        assertThat(count).isEqualTo(0.0);
        verify(repository, never()).save(any());
    }
}
