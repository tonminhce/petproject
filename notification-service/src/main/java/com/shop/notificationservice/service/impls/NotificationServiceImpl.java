package com.shop.notificationservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.NotificationService;
import com.shop.notificationservice.service.NotificationTemplates;
import com.shop.notificationservice.service.NotificationWriter;
import com.shop.notificationservice.service.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository repository;
    private final NotificationWriter writer;
    private final NotificationSender sender;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(OrderLifecycleEvent event) {
        UUID eventId = UUID.fromString(event.getEventId());
        if (repository.existsByEventId(eventId)) {
            log.info("Event {} already processed, skipping", eventId);
            return;
        }
        NotificationTemplates.Draft draft = NotificationTemplates.build(event);
        Notification notification = Notification.builder()
                .eventId(eventId)
                .orderId(event.getOrderId())
                .userId(event.getUserId())
                .eventType(eventTypeOrUnknown(event.getEventType()))
                .status(draft.known() ? NotificationStatus.SENT : NotificationStatus.SKIPPED)
                .channel(draft.known() ? sender.channel() : NotificationChannel.LOG)
                .subject(draft.subject())
                .body(draft.body())
                .payload(payload(event))
                .build();
        Notification saved;
        try {
            saved = writer.insert(notification);
        } catch (DataIntegrityViolationException e) {
            log.info("Event {} already persisted by a concurrent consumer, skipping", eventId);
            return;
        }
        if (!draft.known()) {
            return;
        }
        try {
            sender.send(saved);
        } catch (Exception e) {
            log.error("Notification {} send failed", saved.getId(), e);
            writer.markFailed(saved.getId());
        }
    }

    private String eventTypeOrUnknown(String eventType) {
        return eventType == null || eventType.isBlank() ? "unknown" : eventType;
    }

    private String payload(OrderLifecycleEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }
}
