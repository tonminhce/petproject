package com.shop.shippingservice.service.impls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.shippingservice.config.ShippingWebhookProperties;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentEvent;
import com.shop.shippingservice.repository.ShipmentEventRepository;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.ShipmentStateMachine;
import com.shop.shippingservice.service.WebhookEventService;
import com.shop.shippingservice.service.WebhookEventWriter;
import com.shop.shippingservice.webhook.CarrierWebhookPayload;
import com.shop.shippingservice.webhook.WebhookSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventServiceImpl implements WebhookEventService {

    private static final String EVENT_STATUS_PROCESSED = "PROCESSED";
    private static final String EVENT_STATUS_FAILED = "FAILED";
    private static final String EVENT_TYPE_UNPARSEABLE = "UNPARSEABLE";
    private static final String UNPARSEABLE_EVENT_ID_PREFIX = "unparseable-";
    private static final int PROVIDER_EVENT_ID_MAX_LENGTH = 128;

    private final ShippingWebhookProperties webhookProperties;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventRepository eventRepository;
    private final WebhookEventWriter writer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void handle(String carrier, byte[] rawBody, String signature) {
        Carrier parsedCarrier = resolveCarrier(carrier);
        String secret = webhookProperties.getSecrets().get(parsedCarrier.name());
        if (secret == null || secret.isBlank()
                || !WebhookSignatureVerifier.verify(secret, rawBody, signature)) {
            throw BusinessException.of(ErrorCode.SHIPPING_WEBHOOK_SIGNATURE_INVALID, carrier);
        }

        CarrierWebhookPayload payload;
        try {
            payload = parse(rawBody);
        } catch (IllegalStateException e) {
            log.warn("Webhook body from carrier {} failed to parse, persisting FAILED event", carrier);
            persistUnparseableEvent(parsedCarrier, rawBody);
            return;
        }

        if (eventRepository.existsByCarrierAndProviderEventId(parsedCarrier, payload.getEventId())) {
            log.info("Webhook event {} from carrier {} already processed, acking no-op", payload.getEventId(), carrier);
            return;
        }

        ShipmentEvent event = ShipmentEvent.builder()
                .id(UUID.randomUUID())
                .carrier(parsedCarrier)
                .providerEventId(payload.getEventId())
                .type(payload.getEventType())
                .payload(new String(rawBody, StandardCharsets.UTF_8))
                .status(EVENT_STATUS_FAILED)
                .build();
        try {
            writer.insert(event);
        } catch (DataIntegrityViolationException e) {
            log.info("Webhook event {} from carrier {} raced a concurrent insert, acking no-op",
                    payload.getEventId(), carrier);
            return;
        }

        Shipment shipment = shipmentRepository.findByTrackingNumber(payload.getTrackingNumber()).orElse(null);
        if (shipment == null) {
            log.warn("Webhook event {} references unknown tracking number {}, event marked FAILED",
                    payload.getEventId(), payload.getTrackingNumber());
            return;
        }

        if (payload.getCarrierStatus() == null) {
            log.warn("Webhook event {} has null carrierStatus, event marked FAILED", payload.getEventId());
            return;
        }

        ShipmentStatus next;
        try {
            next = ShipmentStateMachine.transition(shipment.getStatus(),
                    ShipmentStatus.valueOf(payload.getCarrierStatus()));
        } catch (BusinessException | IllegalArgumentException e) {
            log.warn("Webhook event {} transition {} -> {} rejected, event marked FAILED",
                    payload.getEventId(), shipment.getStatus(), payload.getCarrierStatus());
            return;
        }

        Instant now = clock.instant();
        shipment.setPreviousStatus(shipment.getStatus());
        shipment.setStatus(next);
        shipment.setLastCarrierUpdate(now);
        if (next == ShipmentStatus.DELIVERED) {
            shipment.setDeliveredAt(now);
        }

        event.setShipmentId(shipment.getId());
        event.setStatus(EVENT_STATUS_PROCESSED);
        writer.complete(shipment, event, next == ShipmentStatus.DELIVERED);
    }

    private Carrier resolveCarrier(String carrier) {
        try {
            return Carrier.valueOf(carrier);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw BusinessException.of(ErrorCode.SHIPPING_WEBHOOK_SIGNATURE_INVALID, carrier);
        }
    }

    private void persistUnparseableEvent(Carrier carrier, byte[] rawBody) {
        String providerEventId = syntheticEventId(rawBody);
        if (eventRepository.existsByCarrierAndProviderEventId(carrier, providerEventId)) {
            log.info("Unparseable webhook body from carrier {} already recorded, acking no-op", carrier);
            return;
        }
        ShipmentEvent event = ShipmentEvent.builder()
                .id(UUID.randomUUID())
                .carrier(carrier)
                .providerEventId(providerEventId)
                .type(EVENT_TYPE_UNPARSEABLE)
                .payload(new String(rawBody, StandardCharsets.UTF_8))
                .status(EVENT_STATUS_FAILED)
                .build();
        try {
            writer.insert(event);
        } catch (DataIntegrityViolationException e) {
            log.info("Unparseable webhook body from carrier {} raced a concurrent insert, acking no-op", carrier);
        }
    }

    private String syntheticEventId(byte[] rawBody) {
        String id = UNPARSEABLE_EVENT_ID_PREFIX + sha256Hex(rawBody);
        return id.length() > PROVIDER_EVENT_ID_MAX_LENGTH ? id.substring(0, PROVIDER_EVENT_ID_MAX_LENGTH) : id;
    }

    private String sha256Hex(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private CarrierWebhookPayload parse(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, CarrierWebhookPayload.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unparseable webhook payload", e);
        }
    }
}
