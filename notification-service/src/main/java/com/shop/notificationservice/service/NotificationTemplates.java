package com.shop.notificationservice.service;

import com.shop.notificationservice.dto.OrderLifecycleEvent;

public final class NotificationTemplates {

    private NotificationTemplates() {
    }

    public record Draft(String subject, String body, boolean known) {
    }

    public static Draft build(OrderLifecycleEvent e) {
        if (e == null || e.eventType() == null) {
            return new Draft("[skipped] unknown", "null or empty event", false);
        }
        return switch (e.eventType()) {
            case "order.created.v1" -> created(e);
            case "order.updated.v1" -> updated(e);
            case "order.cancelled.v1" -> cancelled(e);
            default -> skipped(e);
        };
    }

    private static Draft created(OrderLifecycleEvent e) {
        int itemCount = e.items() == null ? 0 : e.items().size();
        String subject = String.format("Order %s created", e.orderId());
        String body = String.format("status=%s, subtotal=%s, tax=%s, discount=%s, total=%s, items=%d",
                e.status(), e.subtotal(), e.taxAmount(), e.discountAmount(), e.total(), itemCount);
        return new Draft(subject, body, true);
    }

    private static Draft updated(OrderLifecycleEvent e) {
        String subject = String.format("Order %s → %s", e.orderId(), e.status());
        String body = String.format("status=%s, transitionedAt=%s", e.status(), e.transitionedAt());
        return new Draft(subject, body, true);
    }

    private static Draft cancelled(OrderLifecycleEvent e) {
        String subject = String.format("Order %s cancelled", e.orderId());
        String body = String.format("cancelledAt=%s, refunded=%s", e.cancelledAt(), e.refunded());
        return new Draft(subject, body, true);
    }

    private static Draft skipped(OrderLifecycleEvent e) {
        String subject = String.format("[skipped] %s", e.eventType());
        String body = String.format("eventId=%s, eventType=%s, occurredAt=%s, orderId=%s",
                e.eventId(), e.eventType(), e.occurredAt(), e.orderId());
        return new Draft(subject, body, false);
    }
}
