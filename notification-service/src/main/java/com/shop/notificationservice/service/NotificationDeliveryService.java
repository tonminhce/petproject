package com.shop.notificationservice.service;

import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.sender.NotificationFailureClassifier;
import com.shop.notificationservice.service.sender.NotificationFailureKind;
import com.shop.notificationservice.service.sender.NotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * C12/C17 — executes ONE delivery attempt for an already-claimed (SENDING)
 * notification and settles the row through {@link NotificationWriter}.
 *
 * <p>The ordering contract is the C12 fix: {@code SENT} is written strictly
 * after {@link NotificationSender#send} returns (provider ack) — a failure
 * anywhere in the send window lands the row in {@code FAILED_RETRYABLE}
 * (transient; scheduler retries with backoff) or {@code FAILED_PERMANENT}
 * (invalid recipient / attempt budget exhausted), never in a state that
 * claims success.</p>
 *
 * <p>Shared by the Kafka consumer's initial attempt and the retry scheduler,
 * so both paths settle rows identically.</p>
 */
@Service
@Slf4j
public class NotificationDeliveryService {

    private final NotificationRepository repository;
    private final NotificationWriter writer;
    private final NotificationSender sender;
    private final NotificationRetryPolicy retryPolicy;
    private final NotificationFailureClassifier failureClassifier;
    private final Clock clock;

    public NotificationDeliveryService(NotificationRepository repository,
                                       NotificationWriter writer,
                                       NotificationSender sender,
                                       NotificationRetryPolicy retryPolicy,
                                       NotificationFailureClassifier failureClassifier,
                                       Clock clock) {
        this.repository = repository;
        this.writer = writer;
        this.sender = sender;
        this.retryPolicy = retryPolicy;
        this.failureClassifier = failureClassifier;
        this.clock = clock;
    }

    public void deliver(UUID id) {
        Notification n = repository.findById(id).orElse(null);
        if (n == null) {
            log.warn("Notification {} vanished before delivery; skipping", id);
            return;
        }
        int attempt = n.getRetryCount() + 1;
        try {
            sender.send(n);
            writer.markSent(id);
            log.info("Notification {} delivered on attempt {}", id, attempt);
        } catch (Exception e) {
            boolean permanent = failureClassifier.classify(e) == NotificationFailureKind.PERMANENT
                    || retryPolicy.isExhausted(attempt);
            if (permanent) {
                writer.markPermanent(id, String.valueOf(e));
                log.error("Notification {} permanently failed on attempt {} — no further retries",
                        id, attempt, e);
            } else {
                writer.markRetryable(id, attempt, retryPolicy.nextRetryAt(attempt, clock.instant()),
                        String.valueOf(e));
                log.warn("Notification {} attempt {}/{} failed transiently; retry scheduled",
                        id, attempt, retryPolicy.maxAttempts(), e);
            }
        }
    }
}
