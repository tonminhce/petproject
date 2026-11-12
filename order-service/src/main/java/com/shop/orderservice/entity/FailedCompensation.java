package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A compensation that failed and still has to be done.
 *
 * <p>The checkout saga releases the stock reservations when it fails, and the
 * order row is deleted afterwards. When the release call itself fails, the
 * reservation is orphaned: nothing in the order-service references it any more,
 * so the only way back is inventory-service's TTL sweep - up to
 * {@code inventory.reservation-ttl-seconds} (900 by default) of unavailable
 * stock, with a log line as the only trace. This row is that trace, and it is
 * the work list of the retry.</p>
 */
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

    /** The order the compensation belonged to - the row is deleted, the id stays. */
    @Column(name = "order_id")
    private UUID orderId;

    /** The reservation whose release failed. */
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
