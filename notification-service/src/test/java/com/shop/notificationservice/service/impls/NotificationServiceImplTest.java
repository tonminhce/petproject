package com.shop.notificationservice.service.impls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.NotificationWriter;
import com.shop.notificationservice.service.sender.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NOTIFICATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock NotificationRepository repository;
    @Mock NotificationWriter writer;
    @Mock NotificationSender sender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(repository, writer, sender, objectMapper);
    }

    private OrderLifecycleEvent createdEvent() {
        OrderLifecycleEvent e = new OrderLifecycleEvent();
        e.setEventId(EVENT_ID.toString());
        e.setEventType("order.created.v1");
        e.setOccurredAt("2026-08-30T10:00:00Z");
        e.setOrderId(ORDER_ID);
        e.setUserId(USER_ID);
        e.setStatus("NEW");
        e.setSubtotal(new BigDecimal("100.00"));
        e.setTaxAmount(new BigDecimal("8.00"));
        e.setDiscountAmount(new BigDecimal("10.00"));
        e.setTotal(new BigDecimal("98.00"));
        e.setItems(List.of(Map.of("sku", "A"), Map.of("sku", "B")));
        return e;
    }

    private Notification savedNotification() {
        return Notification.builder()
                .id(NOTIFICATION_ID)
                .eventId(EVENT_ID)
                .orderId(ORDER_ID)
                .status(NotificationStatus.SENT)
                .channel(NotificationChannel.LOG)
                .subject("Order " + ORDER_ID + " created")
                .body("status=NEW")
                .payload("{}")
                .build();
    }

    @Test
    void createdEvent_insertsSentRowAndSends() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        Notification saved = savedNotification();
        when(writer.insert(any(Notification.class))).thenReturn(saved);

        service.handle(createdEvent());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        Notification inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(inserted.getChannel()).isEqualTo(NotificationChannel.LOG);
        assertThat(inserted.getSubject()).isEqualTo("Order " + ORDER_ID + " created");
        assertThat(inserted.getEventId()).isEqualTo(EVENT_ID);
        assertThat(inserted.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inserted.getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getEventType()).isEqualTo("order.created.v1");
        assertThat(inserted.getBody()).contains("subtotal=100.00", "tax=8.00", "discount=10.00", "total=98.00");
        verify(sender).send(saved);
        verify(writer, never()).markFailed(any());
    }

    @Test
    void unknownEventType_insertsSkippedRowAndNeverSends() {
        OrderLifecycleEvent e = createdEvent();
        e.setEventType("order.exploded.v9");
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);

        service.handle(e);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(captor.getValue().getEventType()).isEqualTo("order.exploded.v9");
        verify(sender, never()).send(any());
        verify(writer, never()).markFailed(any());
    }

    @Test
    void nullEventType_routesToSkipped() {
        OrderLifecycleEvent e = createdEvent();
        e.setEventType(null);
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);

        service.handle(e);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(captor.getValue().getEventType()).isEqualTo("unknown");
        verify(sender, never()).send(any());
    }

    @Test
    void blankEventType_storedAsUnknown() {
        OrderLifecycleEvent e = createdEvent();
        e.setEventType("   ");
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);

        service.handle(e);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(writer).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(captor.getValue().getEventType()).isEqualTo("unknown");
        verify(sender, never()).send(any());
    }

    @Test
    void alreadyProcessedEvent_noInsertNoSend() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(true);

        service.handle(createdEvent());

        verify(writer, never()).insert(any());
        verify(sender, never()).send(any());
    }

    @Test
    void duplicateRace_noSendNoCrash() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(writer.insert(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_event_id"));

        assertThatCode(() -> service.handle(createdEvent())).doesNotThrowAnyException();

        verify(sender, never()).send(any());
        verify(writer, never()).markFailed(any());
    }

    @Test
    void senderThrows_marksFailed() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(writer.insert(any(Notification.class))).thenReturn(savedNotification());
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(sender).send(any(Notification.class));

        service.handle(createdEvent());

        verify(writer).markFailed(NOTIFICATION_ID);
    }
}
