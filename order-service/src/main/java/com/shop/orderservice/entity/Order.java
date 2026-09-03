package com.shop.orderservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.orderservice.constant.OrderStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    /**
     * Set when the promotion coordinator reserves a discount; commit/release on
     * confirm/cancel, reconciliation after crashes (hardening §3).
     */
    @Column(name = "promotion_reservation_id")
    private UUID promotionReservationId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "shipping_address", length = 500)
    private String shippingAddress;

    /**
     * Optimistic-lock guard for status transitions (review I3): without it, a
     * concurrent confirm + cancel both pass the state machine and last-write-wins,
     * leaving e.g. a CANCELLED order with confirmedAt set and both lifecycle events
     * published. Mirrors inventory-service's Inventory/Reservation precedent.
     */
    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;
}
