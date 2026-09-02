package com.shop.notificationservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.PENDING;

    // C12/C17 — retry bookkeeping (changelog-002). nextRetryAt doubles as the
    // SENDING heartbeat: the retry scheduler reclaims rows whose heartbeat has
    // elapsed (crash mid-send), so a delivery attempt is never silently lost.
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 8)
    private NotificationChannel channel;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;
}
