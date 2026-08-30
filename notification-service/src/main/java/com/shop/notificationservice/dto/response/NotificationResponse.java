package com.shop.notificationservice.dto.response;

import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID eventId,
        String eventType,
        UUID orderId,
        UUID userId,
        NotificationStatus status,
        NotificationChannel channel,
        String subject,
        Instant createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getEventId(),
                notification.getEventType(),
                notification.getOrderId(),
                notification.getUserId(),
                notification.getStatus(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getCreatedAt());
    }
}
