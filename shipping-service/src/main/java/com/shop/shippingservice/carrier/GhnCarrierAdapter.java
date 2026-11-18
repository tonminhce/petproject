package com.shop.shippingservice.carrier;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
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

    private final CarrierShipmentClient client;
    private final String apiToken;
    private final String shopId;
    private final String endpoint;
    private final String trackingJsonPath;

    public GhnCarrierAdapter(
            CarrierShipmentClient client,
            @Value("${shop.shipping.ghn.token:}") String apiToken,
            @Value("${shop.shipping.ghn.shop-id:}") String shopId,
            @Value("${shop.shipping.ghn.endpoint:}") String endpoint,
            @Value("${shop.shipping.ghn.tracking-json-path:data.tracking_number}") String trackingJsonPath) {
        this.client = client;
        this.apiToken = apiToken;
        this.shopId = shopId;
        this.endpoint = endpoint;
        this.trackingJsonPath = trackingJsonPath;
        requireConfigured();
    }

    private void requireConfigured() {
        if (endpoint == null || endpoint.isBlank()) {
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, Carrier.GHN.name());
        }
        if (apiToken == null || apiToken.isBlank()) {
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, Carrier.GHN.name());
        }
    }

    @Override
    public Carrier carrier() {
        return Carrier.GHN;
    }

    @Override
    public ShipmentDraft createShipment(UUID orderId) {
        String trackingNumber = client.createShipment(
                Carrier.GHN.name(), endpoint, apiToken, shopId, orderId, trackingJsonPath);
        log.info("Created GHN shipment with carrier tracking code {} for order {}", trackingNumber, orderId);
        return new ShipmentDraft(trackingNumber, ShipmentStatus.CREATED);
    }
}
