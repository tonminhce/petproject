package com.shop.shippingservice.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * <p>The GHN and GHTK adapters previously never contacted their carrier: they
 * derived a tracking number locally from the order id and returned it as if the
 * carrier had issued it. The API token fields were injected and never used. The
 * result was a shipment no carrier knew about, carrying a predictable tracking
 * number, which the reconciliation scheduler then auto-delivered after seven
 * days - so the order reached DELIVERED for a parcel that was never shipped.</p>
 * <p>This client performs the real call. The request and response shapes are
 * carrier-specific, so the tracking-number location is configurable per carrier
 * ({@code shop.shipping.<carrier>.tracking-json-path}). If the endpoint is not
 * configured, or the response does not carry a tracking number, the adapter
 * fails loudly with {@link ErrorCode#CARRIER_NOT_CONFIGURED} instead of
 * fabricating a shipment.</p>
 */
@Component
@Slf4j
public class CarrierShipmentClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CarrierShipmentClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Create a shipment with the carrier and return the tracking number it
     * issued.
     *
     * @param carrier    carrier label, used for logging and for the error code
     * @param endpoint   fully configured create-shipment endpoint
     * @param apiToken   carrier API token
     * @param shopId     carrier shop/account id (may be null)
     * @param orderId    the order the shipment belongs to
     * @param trackingJsonPath dot-separated path to the tracking number in the
     *                        carrier response, e.g. {@code data.tracking_number}
     * @return the carrier-issued tracking number
     */
    public String createShipment(String carrier, String endpoint, String apiToken, String shopId,
                                 UUID orderId, String trackingJsonPath) {
        try {
            var response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiToken)
                    .body("{\"order_id\":\"" + orderId + "\"" + (shopId == null ? "" : ",\"shop_id\":\"" + shopId + "\"") + "}")
                    .retrieve()
                    .body(String.class);
            return extractTracking(carrier, response, trackingJsonPath);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Carrier {} create-shipment call failed for order {}", carrier, orderId, ex);
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, carrier);
        }
    }

    private String extractTracking(String carrier, String body, String trackingJsonPath) {
        if (body == null || body.isBlank()) {
            log.error("Carrier {} returned an empty create-shipment response", carrier);
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, carrier);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception ex) {
            log.error("Carrier {} returned a non-JSON create-shipment response: {}", carrier, body);
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, carrier);
        }
        for (String segment : trackingJsonPath.split("\\.")) {
            if (node == null) {
                break;
            }
            node = node.get(segment);
        }
        if (node == null || node.isNull() || node.asText().isBlank()) {
            log.error("Carrier {} response carried no tracking number at path '{}': {}",
                    carrier, trackingJsonPath, body);
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, carrier);
        }
        return node.asText();
    }
}
