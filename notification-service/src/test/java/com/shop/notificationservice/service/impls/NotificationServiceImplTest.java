package com.shop.notificationservice.service.impls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.dto.response.NotificationResponse;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.NotificationDeliveryService;
import com.shop.notificationservice.service.NotificationWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C12/C17 — handle() persists the row as PENDING FIRST (never SENT), then
 * claims it and delegates the actual send to the delivery service. SENT is
 * only ever written by the writer AFTER a provider ack — a SMTP failure in
 * the send window must never leave a row claiming SENT.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NOTIFICATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock NotificationRepository repository;
    @Mock NotificationWriter writer;
    @Mock NotificationDeliveryService delivery;
    @Mock com.shop.notificationservice.service.sender.NotificationSender sender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(repository, writer, sender, delivery, objectMapper, 900);
    }

    /**
     * H26 — OrderLifecycleEvent is a Java record. Per R1, construction goes
     * through the canonical ctor (no Lombok @Builder).
     */
    private OrderLifecycleEvent createdEvent() {
        return createdEvent(EVENT_ID.toString(), "order.created.v1");
    }

    /** Variant used by tests that need to override {@code eventId} or {@code eventType}. */
    private OrderLifecycleEvent createdEvent(String eventId, String eventType) {
        return new OrderLifecycleEvent(
                eventId,
                eventType,
                "2026-08-30T10:00:00Z",
                ORDER_ID,
                USER_ID,
                "NEW",
                new BigDecimal("100.00"),
                new BigDecimal("8.00"),
                new BigDecimal("10.00"),
                new BigDecimal("98.00"),
                null, null, null,
                List.of(Map.of("sku", "A"), Map.of("sku", "B")));
    }

    private Notification savedNotification() {
        return Notification.builder()
                .id(NOTIFICATION_ID)
                .eventId(EVENT_ID)
                .orderId(ORDER_ID)
                .status(NotificationStatus.PENDING)
                .channel(NotificationChannel.LOG)
                .subject("Order " + ORDER_ID + " created")
                .body("status=NEW")
                .payload("{}")
                .build();
    }

    @Test
    void createdEvent_insertsPendingRowThenClaimsAndDelivers() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(writer.insert(any(Notification.class))).thenReturn(savedNotification());
        when(writer.markSending(eq(NOTIFICATION_ID), any(), any())).thenReturn(true);

        service.handle(createdEvent());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        Notification inserted = captor.getValue();
        // C12: the row is born PENDING — never SENT.
        assertThat(inserted.getStatus()).isEqualTo(NotificationStatus.PENDING);
        // C17: PENDING rows are scheduler-eligible from birth (fallback if the
        // instance dies between insert and claim).
        assertThat(inserted.getNextRetryAt()).isNotNull();
        assertThat(inserted.getChannel()).isEqualTo(NotificationChannel.LOG);
        assertThat(inserted.getSubject()).isEqualTo("Order " + ORDER_ID + " created");
        assertThat(inserted.getEventId()).isEqualTo(EVENT_ID);
        assertThat(inserted.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inserted.getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getEventType()).isEqualTo("order.created.v1");
        assertThat(inserted.getBody()).contains("subtotal=100.00", "tax=8.00", "discount=10.00", "total=98.00");

        verify(writer).markSending(eq(NOTIFICATION_ID), any(), any());
        verify(delivery).deliver(NOTIFICATION_ID);
    }

    @Test
    void claimRejected_deliverIsNotCalled() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(writer.insert(any(Notification.class))).thenReturn(savedNotification());
        when(writer.markSending(eq(NOTIFICATION_ID), any(), any())).thenReturn(false);

        service.handle(createdEvent());

        verify(delivery, never()).deliver(any());
    }

    @Test
    void unknownEventType_insertsSkippedRowAndNeverDelivers() {
        OrderLifecycleEvent e = createdEvent(EVENT_ID.toString(), "order.exploded.v9");
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);

        service.handle(e);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(captor.getValue().getEventType()).isEqualTo("order.exploded.v9");
        verify(writer, never()).markSending(any(), any(), any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void nullEventType_routesToSkipped() {
        OrderLifecycleEvent e = createdEvent(EVENT_ID.toString(), null);
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);

        service.handle(e);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(captor.getValue().getEventType()).isEqualTo("unknown");
        verify(writer, never()).markSending(any(), any(), any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void blankEventType_storedAsUnknown() {
        OrderLifecycleEvent e = createdEvent(EVENT_ID.toString(), "   ");
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);

        service.handle(e);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(captor.getValue().getEventType()).isEqualTo("unknown");
        verify(writer, never()).markSending(any(), any(), any());
    }

    @Test
    void nullEventId_isSkippedWithoutThrowing() {
        OrderLifecycleEvent event = createdEvent(null, "order.created.v1");

        assertThatCode(() -> service.handle(event)).doesNotThrowAnyException();

        verify(writer, never()).insert(any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void malformedEventId_isSkippedWithoutThrowing() {
        OrderLifecycleEvent event = createdEvent("not-a-uuid", "order.created.v1");

        assertThatCode(() -> service.handle(event)).doesNotThrowAnyException();

        verify(writer, never()).insert(any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void alreadyProcessedEvent_noInsertNoDelivery() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(true);

        service.handle(createdEvent());

        verify(writer, never()).insert(any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void duplicateRace_noDeliveryNoCrash() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(writer.insert(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_event_id"));

        assertThatCode(() -> service.handle(createdEvent())).doesNotThrowAnyException();

        verify(writer, never()).markSending(any(), any(), any());
        verify(delivery, never()).deliver(any());
    }

    /**
     * H43 — concurrent-dedup proof. N threads race the same event id through
     * {@code handle()}. All N threads see {@code existsByEventId == false} (the
     * race window). One insert wins; every other insert fails with the unique
     * constraint firing. Exactly one delivery happens.
     *
     * <p>This pins the contract that {@code saveAndFlush + catch
     * DataIntegrityViolationException} (the Java equivalent of
     * {@code ON CONFLICT DO NOTHING}) is the safety net — the optimistic
     * {@code existsByEventId} check is only an optimization.</p>
     */
    @Test
    void concurrentDuplicate_insertLosesRaceAndExactlyOneDelivery() throws Exception {
        int parallelism = 4;
        CyclicBarrier barrier = new CyclicBarrier(parallelism);
        AtomicInteger insertAttempts = new AtomicInteger();
        CountDownLatch firstInsertHeld = new CountDownLatch(1);

        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        // First insert blocks on the latch so the other threads pile up
        // inside insert() too — exactly the race window the unique index
        // exists to catch.
        when(writer.insert(any(Notification.class))).thenAnswer(inv -> {
            int attempt = insertAttempts.incrementAndGet();
            if (attempt == 1) {
                // hold the winning insert open until the losers have all tried
                firstInsertHeld.await(5, TimeUnit.SECONDS);
                return savedNotification();
            }
            throw new DataIntegrityViolationException("uk_notification_event_id");
        });
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(writer.markSending(eq(NOTIFICATION_ID), any(), any())).thenReturn(true);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    service.handle(createdEvent());
                }));
            }
            // give the losers time to enter insert() and fail
            Thread.sleep(200);
            firstInsertHeld.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(insertAttempts.get())
                .as("every thread attempted insert inside the race window")
                .isEqualTo(parallelism);
        verify(delivery, times(1)).deliver(NOTIFICATION_ID);
        // markSending only ran for the winning thread; the rest saw no
        // inserted row to claim.
        verify(writer, times(1)).markSending(eq(NOTIFICATION_ID), any(), any());
    }

    @Test
    void findAllByOrderId_nullOrderId_delegatesToUnfilteredNewestFirstFinder() {
        Notification notification = savedNotification();
        PageRequest pageable = PageRequest.of(0, 10);
        when(repository.findAllByOrderByCreatedAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(notification)));

        Page<NotificationResponse> page = service.findAllByOrderId(null, pageable);

        verify(repository).findAllByOrderByCreatedAtDesc(pageable);
        verify(repository, never()).findAllByOrderIdOrderByCreatedAtDesc(any(), any());
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(NOTIFICATION_ID);
        assertThat(page.getContent().get(0).orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void findAllByOrderId_withOrderId_delegatesToDerivedFinder() {
        Notification notification = savedNotification();
        PageRequest pageable = PageRequest.of(1, 5);
        when(repository.findAllByOrderIdOrderByCreatedAtDesc(ORDER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(notification)));

        Page<NotificationResponse> page = service.findAllByOrderId(ORDER_ID, pageable);

        verify(repository).findAllByOrderIdOrderByCreatedAtDesc(ORDER_ID, pageable);
        verify(repository, never()).findAllByOrderByCreatedAtDesc(any());
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(NOTIFICATION_ID);
    }
}
