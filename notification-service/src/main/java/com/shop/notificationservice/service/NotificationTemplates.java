package com.shop.notificationservice.service;

import com.shop.notificationservice.dto.OrderLifecycleEvent;

public final class NotificationTemplates {

    private NotificationTemplates() {
    }

    public record Draft(String subject, String body, boolean known) {
    }

    public static Draft build(OrderLifecycleEvent e) {
        if (e.getEventType() == null) {
            return skipped(e);
        }
        return switch (e.getEventType()) {
            case "order.created.v1" -> created(e);
            case "order.updated.v1" -> updated(e);
            case "order.cancelled.v1" -> cancelled(e);
            default -> skipped(e);
        };
    }

    private static Draft created(OrderLifecycleEvent e) {
        int itemCount = e.getItems() == null ? 0 : e.getItems().size();
        String subject = String.format("Order %s created", e.getOrderId());
        String body = String.format("status=%s, subtotal=%s, tax=%s, discount=%s, total=%s, items=%d",
                e.getStatus(), e.getSubtotal(), e.getTaxAmount(), e.getDiscountAmount(), e.getTotal(), itemCount);
        return new Draft(subject, body, true);
    }

    private static Draft updated(OrderLifecycleEvent e) {
        String subject = String.format("Order %s → %s", e.getOrderId(), e.getStatus());
        String body = String.format("status=%s, transitionedAt=%s", e.getStatus(), e.getTransitionedAt());
        return new Draft(subject, body, true);
    }

    private static Draft cancelled(OrderLifecycleEvent e) {
        String subject = String.format("Order %s cancelled", e.getOrderId());
        String body = String.format("cancelledAt=%s, refunded=%s", e.getCancelledAt(), e.getRefunded());
        return new Draft(subject, body, true);
    }

    private static Draft skipped(OrderLifecycleEvent e) {
        String subject = String.format("[skipped] %s", e.getEventType());
        String body = String.format("eventId=%s, eventType=%s, occurredAt=%s, orderId=%s",
                e.getEventId(), e.getEventType(), e.getOccurredAt(), e.getOrderId());
        return new Draft(subject, body, false);
    }
}
