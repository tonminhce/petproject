package com.shop.common.core.data;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Marker mixin for soft-delete-aware entities.
 *
 * <p>Entities that extend this class gain:
 * <ul>
 *   <li>{@code deleted} — boolean flag (column name {@code deleted})</li>
 *   <li>{@code deletedAt} — instant when the soft-delete happened</li>
 *   <li>{@code deletedBy} — optional actor id (string, flexible across Keycloak id / UUID / email)</li>
 * </ul>
 *
 * <p>The actual filter ("ignore rows where deleted = true") is applied per-entity via
 * Hibernate's {@code @SQLRestriction} in each subclass. This base class only holds
 * the columns + getters/setters for the soft-delete lifecycle.
 *
 * <p>Why a base class and not a Hibernate {@code @Filter}? Hibernate filters require explicit
 * {@code enableFilter} on every session — easy to forget. An annotation on the entity forces
 * the filter to be active on every query without any caller-side setup.
 *
 * <h3>Hibernate access strategy</h3>
 * The fields are annotated with {@code @Column} → Hibernate uses <b>field access</b>
 * (reads/writes via reflection on the field, not via getters/setters). The Lombok-generated
 * getters/setters are still useful for Java code — they coexist with field access just fine.
 */
@MappedSuperclass
@Getter
@Setter(AccessLevel.PROTECTED)  // subclasses + same package can set; outside code should use markDeleted()/markRestored()
public abstract class SoftDeletable {

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 255)
    private String deletedBy;

    /** Atomic operation: mark soft-deleted with actor + timestamp. Prefer this over direct setters. */
    public void markDeleted(String deletedBy) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = deletedBy;
    }

    /** Atomic operation: restore from soft-deleted state. Prefer this over direct setters. */
    public void markRestored() {
        this.deleted = false;
        this.deletedAt = null;
        this.deletedBy = null;
    }
}