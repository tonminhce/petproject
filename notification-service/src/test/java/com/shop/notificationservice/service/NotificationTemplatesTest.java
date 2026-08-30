package com.shop.notificationservice.service;

import com.shop.notificationservice.dto.OrderLifecycleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplatesTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private OrderLifecycleEvent event(String eventType) {
        OrderLifecycleEvent e = new OrderLifecycleEvent();
        e.setEventId(EVENT_ID.toString());
        e.setEventType(eventType);
        e.setOccurredAt("2026-08-30T10:00:00Z");
        e.setOrderId(ORDER_ID);
        e.setUserId(USER_ID);
        return e;
    }

    @Test
    void createdEvent_buildsSubjectAndBody() {
        OrderLifecycleEvent e = event("order.created.v1");
        e.setStatus("NEW");
        e.setSubtotal(new BigDecimal("100.00"));
        e.setTaxAmount(new BigDecimal("8.00"));
        e.setDiscountAmount(new BigDecimal("10.00"));
        e.setTotal(new BigDecimal("98.00"));
        e.setItems(List.of(Map.of("sku", "A"), Map.of("sku", "B")));

        NotificationTemplates.Draft draft = NotificationTemplates.build(e);

        assertThat(draft.known()).isTrue();
        assertThat(draft.subject()).isEqualTo("Order 11111111-1111-1111-1111-111111111111 created");
        assertThat(draft.body()).isEqualTo(
                "status=NEW, subtotal=100.00, tax=8.00, discount=10.00, total=98.00, items=2");
    }

    @Test
    void createdEventWithoutItems_rendersZeroItems() {
        OrderLifecycleEvent e = event("order.created.v1");
        e.setStatus("NEW");
        e.setSubtotal(new BigDecimal("50.00"));
        e.setTaxAmount(new BigDecimal("4.00"));
        e.setDiscountAmount(BigDecimal.ZERO);
        e.setTotal(new BigDecimal("54.00"));

        NotificationTemplates.Draft draft = NotificationTemplates.build(e);

        assertThat(draft.body()).isEqualTo(
                "status=NEW, subtotal=50.00, tax=4.00, discount=0, total=54.00, items=0");
    }

    @Test
    void nullEventType_routesToSkipped() {
        OrderLifecycleEvent e = event(null);

        NotificationTemplates.Draft draft = NotificationTemplates.build(e);

        assertThat(draft.known()).isFalse();
        assertThat(draft.subject()).isEqualTo("[skipped] null");
        assertThat(draft.body()).isEqualTo(
                "eventId=33333333-3333-3333-3333-333333333333, eventType=null, "
                        + "occurredAt=2026-08-30T10:00:00Z, orderId=11111111-1111-1111-1111-111111111111");
    }

    @Test
    void updatedEvent_buildsSubjectAndBody() {
        OrderLifecycleEvent e = event("order.updated.v1");
        e.setUserId(null);
        e.setStatus("PAID");
        e.setTransitionedAt(Instant.parse("2026-08-30T10:15:00Z"));

        NotificationTemplates.Draft draft = NotificationTemplates.build(e);

        assertThat(draft.known()).isTrue();
        assertThat(draft.subject()).isEqualTo("Order 11111111-1111-1111-1111-111111111111 → PAID");
        assertThat(draft.body()).isEqualTo("status=PAID, transitionedAt=2026-08-30T10:15:00Z");
    }

    @Test
    void cancelledEvent_buildsSubjectAndBody() {
        OrderLifecycleEvent e = event("order.cancelled.v1");
        e.setUserId(null);
        e.setCancelledAt(Instant.parse("2026-08-30T11:00:00Z"));
        e.setRefunded(true);

        NotificationTemplates.Draft draft = NotificationTemplates.build(e);

        assertThat(draft.known()).isTrue();
        assertThat(draft.subject()).isEqualTo("Order 11111111-1111-1111-1111-111111111111 cancelled");
        assertThat(draft.body()).isEqualTo("cancelledAt=2026-08-30T11:00:00Z, refunded=true");
    }

    @Test
    void unknownEventType_marksSkipped() {
        OrderLifecycleEvent e = event("order.exploded.v9");

        NotificationTemplates.Draft draft = NotificationTemplates.build(e);

        assertThat(draft.known()).isFalse();
        assertThat(draft.subject()).isEqualTo("[skipped] order.exploded.v9");
        assertThat(draft.body()).isEqualTo(
                "eventId=33333333-3333-3333-3333-333333333333, eventType=order.exploded.v9, "
                        + "occurredAt=2026-08-30T10:00:00Z, orderId=11111111-1111-1111-1111-111111111111");
    }
}
