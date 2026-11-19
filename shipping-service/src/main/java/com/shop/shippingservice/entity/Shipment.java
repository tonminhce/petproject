package com.shop.shippingservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.service.ShipmentStateMachine;
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
@Table(name = "shipments")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment extends AbstractMappedEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false, length = 16)
    private Carrier carrier;

    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Setter(AccessLevel.NONE)
    private ShipmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 24)
    @Setter(AccessLevel.NONE)
    private ShipmentStatus previousStatus;

    @Column(name = "auto_delivered", nullable = false)
    private boolean autoDelivered;

    @Column(name = "last_carrier_update")
    private Instant lastCarrierUpdate;

    @Column(name = "carrier_acknowledged_at")
    private Instant carrierAcknowledgedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    @Column(name = "version")
    private Long version;

    public void transitionTo(ShipmentStatus target) {
        ShipmentStatus next = ShipmentStateMachine.transition(status, target);
        this.previousStatus = status;
        this.status = next;
    }

    public void operatorRetry() {
        ShipmentStatus next = ShipmentStateMachine.operatorRetry(status);
        this.previousStatus = status;
        this.status = next;
    }

    public void applyCarrierStatus(ShipmentStatus target) {
        ShipmentStatus next = ShipmentStateMachine.carrierStatus(status, target);
        if (next != status) {
            this.previousStatus = status;
        }
        this.status = next;
    }

    public void autoDeliver(Instant deliveredAt) {
        ShipmentStatus next = ShipmentStateMachine.autoDeliver(status);
        this.previousStatus = status;
        this.status = next;
        this.autoDelivered = true;
        this.deliveredAt = deliveredAt;
    }
}
