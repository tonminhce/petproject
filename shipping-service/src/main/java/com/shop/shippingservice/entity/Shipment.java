package com.shop.shippingservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "shipments")
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
    private ShipmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 24)
    private ShipmentStatus previousStatus;

    @Column(name = "auto_delivered", nullable = false)
    private boolean autoDelivered;

    @Column(name = "last_carrier_update")
    private Instant lastCarrierUpdate;

    /**
     * When the carrier took the shipment over. Set when the carrier hands
     * back a tracking number, when an operator assigns one, and on the first
     * carrier webhook. The stale sweep uses it as the gate: a shipment the
     * carrier never accepted is never auto-delivered, and the window runs
     * from the acknowledgement when the carrier has not reported since.
     */
    @Column(name = "carrier_acknowledged_at")
    private Instant carrierAcknowledgedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    @Column(name = "version")
    private Long version;
}
