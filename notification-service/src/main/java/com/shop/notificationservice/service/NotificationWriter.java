package com.shop.notificationservice.service;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * C12/C17 — the delivery state machine. This class is the ONLY writer of
 * {@link NotificationStatus} values; every transition is guarded so a row can
 * never be mutated out of a settled state and, critically, {@link
 * NotificationStatus#SENT} is reachable ONLY from {@link
 * NotificationStatus#SENDING} (i.e. after a provider ack) — never from
 * {@link NotificationStatus#PENDING}, which is the C12 loss bug.
 *
 * <p>Transitions:</p>
 * <pre>
 * PENDING ──markSending──▶ SENDING ──markSent──▶ SENT
 *    ▲                      │  │
 *    │        markRetryable │  └─ markPermanent ──▶ FAILED_PERMANENT (terminal)
 *    └── FAILED_RETRYABLE ◀─┘
 * </pre>
 *
 * <p>{@code markSending} doubles as the claim primitive for both the Kafka
 * consumer (initial attempt) and the retry scheduler: claiming writes a
 * heartbeat into {@code next_retry_at}, and a {@code SENDING} row whose
 * heartbeat has elapsed is reclaimable — that is the crash-mid-send recovery
 * path. F3 — {@code notification_failed_permanent_total} increments on every
 * terminal failure so ops can alert on it.</p>
 */
@Service
public class NotificationWriter {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final NotificationRepository repository;
    private final Counter failedPermanentCounter;

    public NotificationWriter(NotificationRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.failedPermanentCounter = meterRegistry.counter("notification_failed_permanent_total");
    }

    @Transactional
    public Notification insert(Notification notification) {
        return repository.saveAndFlush(notification);
    }

    /**
     * Claims {@code id} for a delivery attempt. Allowed from {@code PENDING},
     * {@code FAILED_RETRYABLE}, legacy {@code FAILED}, and a {@code SENDING}
     * row whose heartbeat has elapsed. {@code heartbeatDeadline} is the new
     * heartbeat (now + stale horizon). Returns {@code false} when the row is
     * settled or claimed by a live peer — the caller must not send.
     */
    @Transactional
    public boolean markSending(UUID id, Instant heartbeatDeadline, Instant now) {
        return repository.findById(id).map(n -> {
            if (!claimable(n, now)) {
                return false;
            }
            n.setStatus(NotificationStatus.SENDING);
            n.setNextRetryAt(heartbeatDeadline);
            repository.save(n);
            return true;
        }).orElse(false);
    }

    /** SENDING → SENT. The ONLY path to SENT — call strictly after the sender's ack. */
    @Transactional
    public boolean markSent(UUID id) {
        return repository.findById(id).map(n -> {
            if (n.getStatus() != NotificationStatus.SENDING) {
                return false;
            }
            n.setStatus(NotificationStatus.SENT);
            n.setNextRetryAt(null);
            n.setLastError(null);
            repository.save(n);
            return true;
        }).orElse(false);
    }

    /** SENDING → FAILED_RETRYABLE with the attempt bookkeeping for the scheduler. */
    @Transactional
    public boolean markRetryable(UUID id, int attempt, Instant nextRetryAt, String error) {
        return repository.findById(id).map(n -> {
            if (n.getStatus() != NotificationStatus.SENDING) {
                return false;
            }
            n.setStatus(NotificationStatus.FAILED_RETRYABLE);
            n.setRetryCount(attempt);
            n.setNextRetryAt(nextRetryAt);
            n.setLastError(truncate(error));
            repository.save(n);
            return true;
        }).orElse(false);
    }

    /** SENDING → FAILED_PERMANENT (terminal). Increments the ops counter. */
    @Transactional
    public boolean markPermanent(UUID id, String error) {
        return repository.findById(id).map(n -> {
            if (n.getStatus() != NotificationStatus.SENDING) {
                return false;
            }
            n.setStatus(NotificationStatus.FAILED_PERMANENT);
            n.setLastError(truncate(error));
            repository.save(n);
            failedPermanentCounter.increment();
            return true;
        }).orElse(false);
    }

    private boolean claimable(Notification n, Instant now) {
        return switch (n.getStatus()) {
            case PENDING, FAILED_RETRYABLE, FAILED -> true;
            case SENDING -> n.getNextRetryAt() != null && n.getNextRetryAt().isBefore(now);
            case SENT, SKIPPED, FAILED_PERMANENT -> false;
        };
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }
}
