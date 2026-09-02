package com.shop.notificationservice.scheduler;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.NotificationDeliveryService;
import com.shop.notificationservice.service.NotificationRetryPolicy;
import com.shop.notificationservice.service.NotificationWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C17 — the retry poller. Mirrors payment/shipping WebhookRetrySchedulerTest:
 * state-machine semantics without booting Spring (the PlatformTransactionManager
 * is a no-op stub; tx propagation is proven on a real DB by NotificationRetryIT).
 *
 * <p>The claim must flip every candidate to SENDING with a FRESH heartbeat
 * before any send happens — that is the pessimistic claim that keeps two
 * instances from double-delivering and the crash-recovery horizon for rows
 * abandoned mid-send.</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationRetrySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Mock NotificationRepository repository;
    @Mock NotificationWriter writer;
    @Mock NotificationDeliveryService delivery;

    private NotificationRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus(true);
            }
            @Override
            public void commit(TransactionStatus status) { }
            @Override
            public void rollback(TransactionStatus status) { }
        };
        scheduler = new NotificationRetryScheduler(repository, writer, delivery,
                new NotificationRetryPolicy(6, 300), txManager, 900, 50);
    }

    private Notification candidate(NotificationStatus status, int retryCount) {
        Notification n = Notification.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .eventType("order.created.v1")
                .status(status)
                .retryCount(retryCount)
                .nextRetryAt(NOW.minusSeconds(60))
                .subject("Order created")
                .body("status=NEW")
                .payload("{}")
                .build();
        if (status == NotificationStatus.FAILED) {
            n.setNextRetryAt(null);
        }
        return n;
    }

    @Test
    void poll_claimsCandidatesThenDeliversEach() {
        Notification a = candidate(NotificationStatus.FAILED_RETRYABLE, 1);
        Notification b = candidate(NotificationStatus.PENDING, 0);
        when(repository.findRetryCandidates(eq(NOW), anyCollection(), eq(NotificationStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of(a, b));

        scheduler.poll(NOW);

        assertThat(a.getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(a.getNextRetryAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(b.getStatus()).isEqualTo(NotificationStatus.SENDING);
        verify(repository).save(a);
        verify(repository).save(b);
        verify(delivery).deliver(a.getId());
        verify(delivery).deliver(b.getId());
    }

    @Test
    void poll_legacyFailedRow_isClaimedAndDelivered() {
        Notification legacy = candidate(NotificationStatus.FAILED, 0);
        when(repository.findRetryCandidates(eq(NOW), anyCollection(), eq(NotificationStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of(legacy));

        scheduler.poll(NOW);

        assertThat(legacy.getStatus()).isEqualTo(NotificationStatus.SENDING);
        verify(delivery).deliver(legacy.getId());
    }

    @Test
    void poll_exhaustedRow_goesStraightToPermanentWithoutSending() {
        Notification spent = candidate(NotificationStatus.FAILED_RETRYABLE, 6);
        when(repository.findRetryCandidates(eq(NOW), anyCollection(), eq(NotificationStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of(spent));

        scheduler.poll(NOW);

        verify(writer).markPermanent(eq(spent.getId()), any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void poll_emptyClaim_noWork() {
        when(repository.findRetryCandidates(eq(NOW), anyCollection(), eq(NotificationStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.poll(NOW);

        verify(repository, never()).save(any());
        verify(delivery, never()).deliver(any());
        verify(writer, never()).markPermanent(any(), any());
    }

    @Test
    void poll_respectsBatchSize() {
        when(repository.findRetryCandidates(eq(NOW), anyCollection(), eq(NotificationStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.poll(NOW);

        verify(repository).findRetryCandidates(eq(NOW), anyCollection(), eq(NotificationStatus.FAILED), eq(Pageable.ofSize(50)));
    }
}
