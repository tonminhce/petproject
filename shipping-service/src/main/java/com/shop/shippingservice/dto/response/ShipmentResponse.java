package com.shop.shippingservice.dto.response;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;

import java.time.Instant;
import java.util.UUID;

public record ShipmentResponse(
        UUID id,
        UUID orderId,
        Carrier carrier,
        String trackingNumber,
        ShipmentStatus status,
        ShipmentStatus previousStatus,
        boolean autoDelivered,
        Instant lastCarrierUpdate,
        Instant deliveredAt,
        Long version,
        Instant createdAt
) {

    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getStatus(),
                shipment.getPreviousStatus(),
                shipment.isAutoDelivered(),
                shipment.getLastCarrierUpdate(),
                shipment.getDeliveredAt(),
                shipment.getVersion(),
                shipment.getCreatedAt());
    }
}
