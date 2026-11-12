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

    /**
     * The unguessable tracking capability of this order.
     *
     * <p>Order tracking used to be gated by the phone number alone. The order
     * id is a UUID that leaks routinely (links, screenshots, support tickets,
     * logs), and a phone number is low entropy and frequently known to third
     * parties, so the phone was the only real secret - and an unauthenticated
     * caller could grind through candidates against a known order id. The
     * tracking code is a random token the customer holds, so the token alone
     * authorises the lookup.</p>
     */
    @Column(name = "tracking_code", unique = true)
    private UUID trackingCode;

    /**
     * Failed phone-only tracking attempts for this order. The phone path is
     * kept for the storefront that has no tracking code yet, so it needs an
     * abuse brake: after a few wrong numbers the order is locked out of the
     * phone path for a cool-down window.
     */
    @Column(name = "track_failures", nullable = false)
    @Builder.Default
    private Integer trackFailures = 0;

    /** Until when the phone-only tracking path is locked for this order. */
    @Column(name = "track_locked_until")
    private Instant trackLockedUntil;

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
