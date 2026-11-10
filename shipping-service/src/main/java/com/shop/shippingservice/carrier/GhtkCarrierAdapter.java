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

/**
 * F-067 (P1) — the GHTK carrier adapter.
 *
 * <p>Previously this adapter never contacted GHTK: it derived a tracking
 * number locally from the order id and returned it as if the carrier had issued
 * it, while the injected API token was never used. See {@link GhnCarrierAdapter}
 * for the full consequence.</p>
 *
 * <p>Now the adapter calls the real create-shipment endpoint and stores the
 * tracking number the carrier issued. If the endpoint is not configured, the
 * application context FAILS AT STARTUP with
 * {@link ErrorCode#CARRIER_NOT_CONFIGURED} rather than silently fabricating
 * shipments.</p>
 */
@Component
@ConditionalOnProperty(name = "shop.shipping.carrier", havingValue = "ghtk")
@Slf4j
public class GhtkCarrierAdapter implements CarrierAdapter {

    private final CarrierShipmentClient client;
    private final String apiToken;
    private final String endpoint;
    private final String trackingJsonPath;

    public GhtkCarrierAdapter(
            CarrierShipmentClient client,
            @Value("${shop.shipping.ghtk.token:}") String apiToken,
            @Value("${shop.shipping.ghtk.endpoint:}") String endpoint,
            @Value("${shop.shipping.ghtk.tracking-json-path:data.tracking_number}") String trackingJsonPath) {
        this.client = client;
        this.apiToken = apiToken;
        this.endpoint = endpoint;
        this.trackingJsonPath = trackingJsonPath;
        requireConfigured();
    }

    private void requireConfigured() {
        if (endpoint == null || endpoint.isBlank()) {
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, Carrier.GHTK.name());
        }
        if (apiToken == null || apiToken.isBlank()) {
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, Carrier.GHTK.name());
        }
    }

    @Override
    public Carrier carrier() {
        return Carrier.GHTK;
    }

    @Override
    public ShipmentDraft createShipment(UUID orderId) {
        String trackingNumber = client.createShipment(
                Carrier.GHTK.name(), endpoint, apiToken, null, orderId, trackingJsonPath);
        log.info("Created GHTK shipment with carrier tracking code {} for order {}", trackingNumber, orderId);
        return new ShipmentDraft(trackingNumber, ShipmentStatus.CREATED);
    }
}
