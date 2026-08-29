package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ⚠️ P0-1 — Does NOT extend {@code AbstractMappedEntity} (which extends
 * {@code SoftDeletable} requiring `deleted` column). Order items are hard-deleted
 * with their parent Order via {@code ON DELETE CASCADE} on the FK.
 *
 * <p>If we extended AbstractMappedEntity, {@code ddl-auto: validate} would fail at
 * boot because the {@code order_items} table has no audit/soft-delete columns.</p>
 */
@Entity
@Table(name = "order_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_title", nullable = false, length = 255)
    private String productTitle;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "reservation_id")
    private UUID reservationId;  // nullable — populated after stock reservation
}
