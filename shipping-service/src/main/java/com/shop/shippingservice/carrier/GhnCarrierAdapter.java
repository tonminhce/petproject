package com.shop.shippingservice.carrier;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.shipping.carrier", havingValue = "ghn")
@Slf4j
public class GhnCarrierAdapter implements CarrierAdapter {

    private final String apiToken;
    private final String shopId;

    public GhnCarrierAdapter(
            @Value("${shop.shipping.ghn.token:GHN_DEMO_TOKEN}") String apiToken,
            @Value("${shop.shipping.ghn.shop-id:123456}") String shopId) {
        this.apiToken = apiToken;
        this.shopId = shopId;
    }

    @Override
    public Carrier carrier() {
        return Carrier.GHN;
    }

    @Override
    public ShipmentDraft createShipment(UUID orderId) {
        String trackingNumber = "GHN" + orderId.toString().replace("-", "").substring(0, 10).toUpperCase();
        log.info("Created GHN shipment with tracking code {} for order {}", trackingNumber, orderId);
        return new ShipmentDraft(trackingNumber, ShipmentStatus.CREATED);
    }
}
