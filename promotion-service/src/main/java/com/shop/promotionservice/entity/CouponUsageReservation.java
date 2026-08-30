package com.shop.promotionservice.entity;

import com.shop.promotionservice.constant.UsageStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Coupon usage held for a pending order. Hard-delete (retention purge)
 * semantics — deliberately does NOT extend {@code AbstractMappedEntity}
 * (spec D10): there is nothing to audit, {@code reservedAt} serves as the
 * creation timestamp, and terminal rows are purged after 30 days.
 */
@Entity
@Table(name = "coupon_usage_reservation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponUsageReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal orderAmount;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private UsageStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "released_at")
    private Instant releasedAt;
}
