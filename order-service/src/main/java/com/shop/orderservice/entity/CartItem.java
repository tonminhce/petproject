package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * P0-1 — Does NOT extend {@code AbstractMappedEntity}. Cart items are hard-deleted
 * with their parent Cart via {@code ON DELETE CASCADE} on the FK.
 *
 * <p>If we extended AbstractMappedEntity, {@code ddl-auto: validate} would fail at
 * boot because the {@code cart_items} table has no audit/soft-delete columns.</p>
 */
@Entity
@Table(name = "cart_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_title", nullable = false, length = 255)
    private String productTitle;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Integer quantity;
}
