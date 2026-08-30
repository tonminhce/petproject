package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.shipping.carrier", havingValue = "noop")
public class NoopCarrierAdapter implements CarrierAdapter {

    @Override
    public Carrier carrier() {
        return Carrier.NOOP;
    }

    @Override
    public ShipmentDraft createShipment(UUID orderId) {
        return new ShipmentDraft("NOOP-" + orderId, ShipmentStatus.PICKED_UP);
    }
}
