package com.shop.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stock record for a product. Hard-delete semantics - NOT soft-deletable.
 * {@code @Version} provides optimistic locking to prevent lost updates
 * when concurrent reserve/commit/release operations touch the same row.
 *
 * <p>Deliberately does NOT extend {@code AbstractMappedEntity}: a stock row is
 * mutable numeric state, not an audited business record — it carries a single
 * {@code lastUpdated} marker instead of the audit columns, which keeps the
 * hot reserve/commit/release writes free of extra UPDATE-wide column churn.
 * {@code OutboxEvent} in this module is also a flat class — the divergence
 * from product-service is intentional on both entities (see spec §3.4).</p>
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(name = "available_quantity", nullable = false)
    @Builder.Default
    private Integer availableQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    @Column(name = "safety_stock_threshold", nullable = false)
    @Builder.Default
    private Integer safetyStockThreshold = 10;

    @Version
    private Long version;

    @Column(name = "last_updated")
    private Instant lastUpdated;
}
