package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.shipping.carrier", havingValue = "manual", matchIfMissing = true)
public class ManualCarrierAdapter implements CarrierAdapter {

    @Override
    public Carrier carrier() {
        return Carrier.MANUAL;
    }

    @Override
    public ShipmentDraft createShipment(UUID orderId) {
        return new ShipmentDraft(null, ShipmentStatus.CREATED);
    }
}
