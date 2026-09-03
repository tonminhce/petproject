package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.shipping.carrier", havingValue = "ghtk")
@Slf4j
public class GhtkCarrierAdapter implements CarrierAdapter {

    private final String apiToken;

    public GhtkCarrierAdapter(
            @Value("${shop.shipping.ghtk.token:GHTK_DEMO_TOKEN}") String apiToken) {
        this.apiToken = apiToken;
    }

    @Override
    public Carrier carrier() {
        return Carrier.GHTK;
    }

    @Override
    public ShipmentDraft createShipment(UUID orderId) {
        String trackingNumber = "GHTK." + orderId.toString().replace("-", "").substring(0, 9).toUpperCase();
        log.info("Created GHTK shipment with tracking code {} for order {}", trackingNumber, orderId);
        return new ShipmentDraft(trackingNumber, ShipmentStatus.CREATED);
    }
}
