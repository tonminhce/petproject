package com.shop.shippingservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.shippingservice.constant.Carrier;
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

import java.util.UUID;

@Entity
@Table(name = "shipment_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipmentEvent extends AbstractMappedEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false, length = 16)
    private Carrier carrier;

    @Column(name = "provider_event_id", nullable = false, length = 128)
    private String providerEventId;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Version
    @Column(name = "version")
    private Long version;
}
