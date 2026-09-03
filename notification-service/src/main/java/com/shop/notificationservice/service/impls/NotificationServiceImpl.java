package com.shop.notificationservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.dto.response.NotificationResponse;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.NotificationDeliveryService;
import com.shop.notificationservice.service.NotificationService;
import com.shop.notificationservice.service.NotificationTemplates;
import com.shop.notificationservice.service.NotificationWriter;
import com.shop.notificationservice.service.sender.NotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * C12/C17 — the consumer entrypoint persists the event as {@code PENDING}
 * FIRST (the durable record of "we owe this user a notification"), then
 * claims it ({@code PENDING → SENDING} heartbeat) and delegates the send to
 * {@link NotificationDeliveryService}, which writes {@code SENT} only after
 * a provider ack or schedules a bounded retry on failure. The claim also
 * arbitrates against the retry scheduler — whoever claims first delivers;
 * the loser skips.
 */
@Service
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository repository;
    private final NotificationWriter writer;
    private final NotificationSender sender;
    private final NotificationDeliveryService delivery;
    private final ObjectMapper objectMapper;
    private final long claimStaleSeconds;

    public NotificationServiceImpl(NotificationRepository repository,
                                   NotificationWriter writer,
                                   NotificationSender sender,
                                   NotificationDeliveryService delivery,
                                   ObjectMapper objectMapper,
                                   @Value("${shop.notification.retry.stale-sending-seconds:900}")
                                   long claimStaleSeconds) {
        this.repository = repository;
        this.writer = writer;
        this.sender = sender;
        this.delivery = delivery;
        this.objectMapper = objectMapper;
        this.claimStaleSeconds = claimStaleSeconds;
    }

    @Override
    public void handle(OrderLifecycleEvent event) {
        UUID eventId = parseEventId(event);
        if (eventId == null) {
            return;
        }
        if (repository.existsByEventId(eventId)) {
            log.info("Event {} already processed, skipping", eventId);
            return;
        }
        NotificationTemplates.Draft draft = NotificationTemplates.build(event);
        Notification notification = Notification.builder()
                .eventId(eventId)
                .orderId(event.orderId())
                .userId(event.userId())
                .eventType(eventTypeOrUnknown(event.eventType()))
                .status(draft.known() ? NotificationStatus.PENDING : NotificationStatus.SKIPPED)
                .channel(draft.known() ? sender.channel() : NotificationChannel.LOG)
                .subject(draft.subject())
                .body(draft.body())
                .payload(payload(event))
                // Scheduler-eligible from birth: if this instance dies before
                // claiming, the retry poller picks the PENDING row up.
                .nextRetryAt(draft.known() ? Instant.now() : null)
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
        if (writer.markSending(saved.getId(), heartbeatDeadline(), Instant.now())) {
            delivery.deliver(saved.getId());
        } else {
            log.info("Notification {} already claimed by another worker; skipping initial send",
                    saved.getId());
        }
    }

    /**
     * Parses the event identifier at the Kafka trust boundary. Invalid payloads
     * are poison messages: log and acknowledge them rather than retrying a
     * value that can never produce a valid notification row.
     */
    private UUID parseEventId(OrderLifecycleEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            log.warn("Skipping notification event with missing eventId");
            return null;
        }
        try {
            return UUID.fromString(event.eventId());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping notification event with invalid eventId {}", event.eventId());
            return null;
        }
    }

    private Instant heartbeatDeadline() {
        return Instant.now().plus(Duration.ofSeconds(claimStaleSeconds));
    }

    @Override
    public Page<NotificationResponse> findAllByOrderId(UUID orderId, Pageable pageable) {
        Page<Notification> page = orderId == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findAllByOrderIdOrderByCreatedAtDesc(orderId, pageable);
        return page.map(NotificationResponse::from);
    }

    @Override
    public NotificationResponse findById(UUID id) {
        return repository.findById(id)
                .map(NotificationResponse::from)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOTIFICATION_NOT_FOUND, id));
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
