package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;

import java.util.UUID;

public interface CarrierAdapter {

    Carrier carrier();

    ShipmentDraft createShipment(UUID orderId);

    record ShipmentDraft(String trackingNumber, ShipmentStatus initialStatus) {
    }
}
