package com.shop.notificationservice.scheduler;

import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.sender.NotificationSender;
import com.shop.notificationservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * C17 — scheduler semantics on a REAL Postgres: the pessimistic claim query
 * compiles and runs, claims flip rows to SENDING with a heartbeat, and the
 * delivery loop settles rows through the writer. Real sender bean
 * ({@code LoggingNotificationSender} primary) — stubbed per test for the
 * failure path.
 *
 * <p>Backoff base is pinned to 1s so the second poll (with an advanced
 * clock) re-claims the FAILED_RETRYABLE row deterministically instead of
 * waiting the 5-minute production default.</p>
 */
class NotificationRetryIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationRetryScheduler retryScheduler;

    @MockitoSpyBean("primary")
    private NotificationSender sender;

    @DynamicPropertySource
    static void retryProps(DynamicPropertyRegistry registry) {
        // Deterministic retry backoff for the IT; the STARTUP immediate run of
        // @Scheduled(fixedDelay) is harmless (only leftover rows exist, all
        // settled or inside their backoff window), and the NEXT scheduled run
        // is an hour out — manual poll(now) calls drive every scenario here.
        registry.add("shop.notification.retry.backoff-base-seconds", () -> "1");
        registry.add("shop.notification.retry.poll-ms", () -> "3600000");
        registry.add("shop.notification.retry.initial-delay-ms", () -> "3600000");
    }

    private Notification newRow(NotificationStatus status, int retryCount, Instant nextRetryAt) {
        Notification n = Notification.builder()
                .eventId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .userId(null)
                .eventType("order.created.v1")
                .status(status)
                .channel(NotificationChannel.LOG)
                .subject("Order retry-it")
                .body("status=NEW")
                .payload("{}")
                .retryCount(retryCount)
                .nextRetryAt(nextRetryAt)
                .build();
        return notificationRepository.saveAndFlush(n);
    }

    @Test
    void poll_deliversPendingRow_andMarksSent() {
        Instant t0 = Instant.now();
        Notification n = newRow(NotificationStatus.PENDING, 0, t0.minusSeconds(60));

        retryScheduler.poll(t0.plusSeconds(1));

        Notification row = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getNextRetryAt()).isNull();
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void poll_retryLoop_transientFailureBacksOffThenSucceeds() {
        Instant t0 = Instant.now();
        Notification n = newRow(NotificationStatus.FAILED_RETRYABLE, 0, t0.minusSeconds(60));
        doThrow(new RuntimeException("smtp-down")).doCallRealMethod().when(sender).send(any());

        retryScheduler.poll(t0);

        Notification afterFail = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(afterFail.getStatus()).isEqualTo(NotificationStatus.FAILED_RETRYABLE);
        assertThat(afterFail.getRetryCount()).isEqualTo(1);
        assertThat(afterFail.getLastError()).contains("smtp-down");
        assertThat(afterFail.getNextRetryAt()).isAfter(t0); // backoff scheduled

        // Advance past the 1s backoff window; the retry succeeds.
        retryScheduler.poll(t0.plusSeconds(10));

        Notification afterRetry = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(afterRetry.getRetryCount()).isEqualTo(1);
        assertThat(afterRetry.getLastError()).isNull();
        assertThat(afterRetry.getNextRetryAt()).isNull();
    }

    @Test
    void poll_staleSendingRow_isReclaimed() {
        Instant t0 = Instant.now();
        // Heartbeat elapsed → the crash-mid-send recovery path.
        Notification n = newRow(NotificationStatus.SENDING, 2, t0.minusSeconds(60));

        retryScheduler.poll(t0);

        Notification row = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(sender).send(any());
    }

    @Test
    void poll_legacyFailedRow_isReclaimed() {
        Instant t0 = Instant.now();
        Notification n = newRow(NotificationStatus.FAILED, 0, null);

        retryScheduler.poll(t0);

        Notification row = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void poll_exhaustedRow_isTerminalWithoutSending() {
        Instant t0 = Instant.now();
        Notification n = newRow(NotificationStatus.FAILED_RETRYABLE, 6, t0.minusSeconds(60));

        retryScheduler.poll(t0);

        Notification row = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.FAILED_PERMANENT);
        assertThat(row.getLastError()).contains("Exceeded max attempts");
        verify(sender, never()).send(any());
    }
}
