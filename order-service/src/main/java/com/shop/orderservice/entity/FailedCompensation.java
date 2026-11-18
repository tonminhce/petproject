package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "failed_compensations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FailedCompensation {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";

    public static final String KIND_RESERVATION_RELEASE = "RESERVATION_RELEASE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
