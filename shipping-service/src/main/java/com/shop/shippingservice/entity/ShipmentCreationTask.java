package com.shop.shippingservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentCreationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_creation_tasks")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipmentCreationTask extends AbstractMappedEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false, length = 16)
    private Carrier carrier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Setter(AccessLevel.NONE)
    private ShipmentCreationStatus status;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    @Setter(AccessLevel.NONE)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    @Setter(AccessLevel.NONE)
    private Instant claimedAt;

    @Column(name = "tracking_number", length = 64)
    @Setter(AccessLevel.NONE)
    private String trackingNumber;

    @Column(name = "shipment_id")
    @Setter(AccessLevel.NONE)
    private UUID shipmentId;

    @Column(name = "last_error", length = 1000)
    @Setter(AccessLevel.NONE)
    private String lastError;

    @Version
    @Column(name = "version")
    private Long version;

    public void claim(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public void complete(UUID shipmentId, String trackingNumber) {
        this.status = ShipmentCreationStatus.COMPLETED;
        this.shipmentId = shipmentId;
        this.trackingNumber = trackingNumber;
        this.claimedAt = null;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void reschedule(int attemptCount, Instant nextAttemptAt, String lastError) {
        this.status = ShipmentCreationStatus.PENDING;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.claimedAt = null;
    }

    public void failPermanently(int attemptCount, String lastError) {
        this.status = ShipmentCreationStatus.FAILED_PERMANENT;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.claimedAt = null;
        this.nextAttemptAt = null;
    }

    public void reopen(Carrier carrier, Instant nextAttemptAt) {
        this.carrier = carrier;
        this.status = ShipmentCreationStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = nextAttemptAt;
        this.claimedAt = null;
        this.lastError = null;
        this.shipmentId = null;
        this.trackingNumber = null;
    }
}
