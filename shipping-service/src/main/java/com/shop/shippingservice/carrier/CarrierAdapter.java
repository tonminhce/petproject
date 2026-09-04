package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;

import java.util.UUID;

public interface CarrierAdapter {

    Carrier carrier();

    default String carrierCode() {
        Carrier c = carrier();
        return (c != null) ? c.name() : "MANUAL";
    }

    ShipmentDraft createShipment(UUID orderId);

    record ShipmentDraft(String trackingNumber, ShipmentStatus initialStatus) {
    }
}
