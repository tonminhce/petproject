package com.shop.common.core.data;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@MappedSuperclass
@Getter
@Setter(AccessLevel.PROTECTED)
public abstract class SoftDeletable {

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 255)
    private String deletedBy;

    public void markDeleted(String deletedBy) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = deletedBy;
    }

    public void markRestored() {
        this.deleted = false;
        this.deletedAt = null;
        this.deletedBy = null;
    }
}