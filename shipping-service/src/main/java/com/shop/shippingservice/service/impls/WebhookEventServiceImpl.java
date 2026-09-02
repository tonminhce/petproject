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
import com.shop.shippingservice.service.ShippingMetrics;
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
import java.util.Optional;
import java.util.UUID;

/**
 * C3 — webhook entry + retry. Same shape as payment-service's WebhookEventService:
 * events start as {@link ShipmentEvent#STATUS_FAILED_RETRYABLE}, are processed
 * once, and the new {@code WebhookRetryScheduler} picks up any that didn't
 * reach {@link ShipmentEvent#STATUS_PROCESSED}. After max retries the row
 * transitions to {@link ShipmentEvent#STATUS_FAILED_PERMANENT}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventServiceImpl implements WebhookEventService {

    private static final String EVENT_TYPE_UNPARSEABLE = "UNPARSEABLE";
    private static final String UNPARSEABLE_EVENT_ID_PREFIX = "unparseable-";
    private static final int PROVIDER_EVENT_ID_MAX_LENGTH = 128;

    private final ShippingWebhookProperties webhookProperties;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventRepository eventRepository;
    private final WebhookEventWriter writer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ShippingMetrics metrics;

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
            log.warn("Webhook body from carrier {} failed to parse, persisting FAILED_RETRYABLE event", carrier);
            persistUnparseableEvent(parsedCarrier, rawBody);
            return;
        }

        // C3 — dedup: skip only PROCESSED rows. FAILED_RETRYABLE rows fall through
        // so the retry scheduler can re-process them.
        Optional<ShipmentEvent> existing = eventRepository
                .findFirstByCarrierAndProviderEventId(parsedCarrier, payload.getEventId());
        if (existing.isPresent() && ShipmentEvent.STATUS_PROCESSED.equals(existing.get().getStatus())) {
            log.info("Webhook event {} from carrier {} already processed, acking no-op", payload.getEventId(), carrier);
            return;
        }
        ShipmentEvent event;
        if (existing.isEmpty()) {
            event = ShipmentEvent.builder()
                    .id(UUID.randomUUID())
                    .carrier(parsedCarrier)
                    .providerEventId(payload.getEventId())
                    .type(payload.getEventType())
                    .payload(new String(rawBody, StandardCharsets.UTF_8))
                    .status(ShipmentEvent.STATUS_FAILED_RETRYABLE)
                    .retryCount(0)
                    .nextRetryAt(Instant.now())
                    .build();
            try {
                writer.insert(event);
            } catch (DataIntegrityViolationException e) {
                log.info("Webhook event {} from carrier {} raced a concurrent insert, acking no-op",
                        payload.getEventId(), carrier);
                return;
            }
        } else {
            event = existing.get();
        }

        try {
            process(payload, event);
        } catch (Exception e) {
            log.error("Webhook processing failed (carrier={}, providerEventId={})", carrier, payload.getEventId(), e);
            markFailedRetryable(event, e.getMessage());
        }
    }

    @Override
    public void retry(ShipmentEvent event) {
        byte[] rawBody = event.getPayload() == null
                ? new byte[0]
                : event.getPayload().getBytes(StandardCharsets.UTF_8);
        CarrierWebhookPayload payload;
        try {
            payload = parse(rawBody);
        } catch (IllegalStateException e) {
            event.setStatus(ShipmentEvent.STATUS_FAILED_PERMANENT);
            event.setLastError("payload unparseable on retry");
            writer.saveEvent(event);
            return;
        }
        try {
            process(payload, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void process(CarrierWebhookPayload payload, ShipmentEvent event) {
        Shipment shipment = shipmentRepository.findByTrackingNumber(payload.getTrackingNumber()).orElse(null);
        if (shipment == null) {
            // Unknown tracking — almost always a carrier mistake. Throw a retryable
            // error so the scheduler eventually surfaces FAILED_PERMANENT and ops
            // can investigate. We avoid logging inside the exception path because
            // WebhookRetryScheduler will log attempt context.
            throw BusinessException.badRequest("shipping.webhook.unknown.tracking",
                payload.getTrackingNumber());
        }
        if (payload.getCarrierStatus() == null) {
            throw BusinessException.badRequest("shipping.webhook.missing.status");
        }
        ShipmentStatus next;
        try {
            next = ShipmentStateMachine.transition(shipment.getStatus(),
                    ShipmentStatus.valueOf(payload.getCarrierStatus()));
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("shipping.webhook.invalid.status",
                payload.getCarrierStatus());
        }

        ShipmentStatus from = shipment.getStatus();
        Instant now = clock.instant();
        shipment.setPreviousStatus(from);
        shipment.setStatus(next);
        shipment.setLastCarrierUpdate(now);
        if (next == ShipmentStatus.DELIVERED) {
            shipment.setDeliveredAt(now);
            metrics.recordDelivered(false);
        }

        event.setShipmentId(shipment.getId());
        event.setStatus(ShipmentEvent.STATUS_PROCESSED);
        writer.complete(shipment, event, next == ShipmentStatus.DELIVERED);
        metrics.recordAdvance(from, next);
        if (next == ShipmentStatus.DELIVERY_FAILED) {
            metrics.recordFailed();
        }
    }

    private void markFailedRetryable(ShipmentEvent event, String error) {
        event.setStatus(ShipmentEvent.STATUS_FAILED_RETRYABLE);
        event.setLastError(truncate(error, 1024));
        writer.saveEvent(event);
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
        Optional<ShipmentEvent> existing = eventRepository
                .findFirstByCarrierAndProviderEventId(carrier, providerEventId);
        if (existing.isPresent()) {
            return; // already on the retry queue
        }
        ShipmentEvent event = ShipmentEvent.builder()
                .id(UUID.randomUUID())
                .carrier(carrier)
                .providerEventId(providerEventId)
                .type(EVENT_TYPE_UNPARSEABLE)
                .payload(new String(rawBody, StandardCharsets.UTF_8))
                .status(ShipmentEvent.STATUS_FAILED_RETRYABLE)
                .retryCount(0)
                .nextRetryAt(Instant.now())
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
