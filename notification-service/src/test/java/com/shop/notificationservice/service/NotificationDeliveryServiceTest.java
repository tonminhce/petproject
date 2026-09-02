package com.shop.notificationservice.service;

import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.sender.NotificationFailureClassifier;
import com.shop.notificationservice.service.sender.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C12 — the SENT transition happens strictly AFTER {@code sender.send}
 * returns (provider ack). A failure inside the send window must land the row
 * in FAILED_RETRYABLE / FAILED_PERMANENT — never leave it claiming SENT.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Mock NotificationRepository repository;
    @Mock NotificationWriter writer;
    @Mock NotificationSender sender;

    private NotificationDeliveryService delivery;

    @BeforeEach
    void setUp() {
        // maxAttempts=3 so the exhaustion path is reachable at attempt 3.
        delivery = new NotificationDeliveryService(repository, writer, sender,
                new NotificationRetryPolicy(3, 300), new NotificationFailureClassifier(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Notification sendingRow(int retryCount) {
        Notification n = Notification.builder()
                .id(ID)
                .subject("Order created")
                .body("status=NEW")
                .build();
        n.setStatus(com.shop.notificationservice.constant.NotificationStatus.SENDING);
        n.setRetryCount(retryCount);
        return n;
    }

    @Test
    void sendAcked_marksSentOnlyAfterSendReturns() {
        Notification n = sendingRow(0);
        when(repository.findById(ID)).thenReturn(Optional.of(n));

        delivery.deliver(ID);

        InOrder order = inOrder(sender, writer);
        order.verify(sender).send(n);
        order.verify(writer).markSent(ID);
        verify(writer, never()).markRetryable(any(), anyInt(), any(), anyString());
        verify(writer, never()).markPermanent(any(), anyString());
    }

    @Test
    void transientFailure_schedulesRetryableWithAttemptAndBackoff() {
        Notification n = sendingRow(0);
        when(repository.findById(ID)).thenReturn(Optional.of(n));
        doThrow(new IllegalStateException("socket timeout")).when(sender).send(n);

        delivery.deliver(ID);

        verify(writer, never()).markSent(any());
        verify(writer).markRetryable(eq(ID), eq(1), eq(NOW.plusSeconds(300)),
                eq("java.lang.IllegalStateException: socket timeout"));
        verify(writer, never()).markPermanent(any(), anyString());
    }

    @Test
    void permanentFailure_goesStraightToFailedPermanent_noRetryScheduled() {
        Notification n = sendingRow(0);
        when(repository.findById(ID)).thenReturn(Optional.of(n));
        doThrow(new IllegalStateException("wrapper",
                new MailSendException("Invalid Addresses"))).when(sender).send(n);

        delivery.deliver(ID);

        verify(writer).markPermanent(eq(ID), anyString());
        verify(writer, never()).markRetryable(any(), anyInt(), any(), anyString());
        verify(writer, never()).markSent(any());
    }

    @Test
    void transientFailureBeyondMaxAttempts_isPermanent() {
        Notification n = sendingRow(2); // attempt 3 == maxAttempts
        when(repository.findById(ID)).thenReturn(Optional.of(n));
        doThrow(new IllegalStateException("still down")).when(sender).send(n);

        delivery.deliver(ID);

        verify(writer).markPermanent(eq(ID), anyString());
        verify(writer, never()).markRetryable(any(), anyInt(), any(), anyString());
    }

    @Test
    void missingRow_isContained() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThatCode(() -> delivery.deliver(ID)).doesNotThrowAnyException();

        verify(sender, never()).send(any());
        verify(writer, never()).markSent(any());
    }
}
