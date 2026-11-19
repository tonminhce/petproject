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

@Component
@Slf4j
public class CarrierShipmentClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CarrierShipmentClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public String createShipment(String carrier, String endpoint, String apiToken, String shopId,
                                 UUID orderId, String trackingJsonPath) {
        try {
            var response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Idempotency-Key", orderId.toString())
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
