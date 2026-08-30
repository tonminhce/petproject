package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.webhook.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventService {

    private static final String UNPARSEABLE_PREFIX = "unparseable-";
    private static final String INVALID_PREFIX = "invalid-";
    private static final String UNKNOWN_TYPE = "unknown";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository eventRepository;
    private final PaymentWriter writer;
    private final ObjectMapper objectMapper;

    public void handle(String provider, byte[] rawBody) {
        WebhookPayload payload = tryParse(rawBody);
        String providerEventId = resolveProviderEventId(payload, rawBody);
        if (eventRepository.existsByProviderAndProviderEventId(provider, providerEventId)) {
            return;
        }
        PaymentEvent event = buildEvent(provider, providerEventId, payload, rawBody);
        try {
            writer.insertEvent(event);
        } catch (DataIntegrityViolationException e) {
            log.debug("Webhook event already stored (provider={}, providerEventId={})", provider, providerEventId);
            return;
        }
        if (payload == null || !hasCriticalFields(payload)) {
            return;
        }
        try {
            process(payload, event);
        } catch (Exception e) {
            log.error("Webhook processing failed (provider={}, providerEventId={})", provider, providerEventId, e);
            writer.markEventFailed(event);
        }
    }

    private void process(WebhookPayload payload, PaymentEvent event) {
        UUID paymentId = parsePaymentId(payload.paymentId());
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId));
        if (payload.amount() == null || payment.getAmount() == null
                || payload.amount().compareTo(payment.getAmount()) != 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, payload.amount(), payment.getAmount());
        }
        PaymentStatus target = PaymentStatus.valueOf(payload.status());
        PaymentStatus next = PaymentStateMachine.transition(payment.getStatus(), target);
        payment.setPreviousStatus(payment.getStatus());
        payment.setStatus(next);
        writer.completeWithEvent(payment, event, outboxEventType(next));
    }

    private WebhookPayload tryParse(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasCriticalFields(WebhookPayload payload) {
        return payload.paymentId() != null && !payload.paymentId().isBlank()
                && parseStatus(payload.status()) != null;
    }

    private PaymentStatus parseStatus(String status) {
        return switch (status == null ? "" : status) {
            case "CAPTURED" -> PaymentStatus.CAPTURED;
            case "FAILED" -> PaymentStatus.FAILED;
            case "REFUNDED" -> PaymentStatus.REFUNDED;
            default -> null;
        };
    }

    private PaymentEvent buildEvent(String provider, String providerEventId, WebhookPayload payload, byte[] rawBody) {
        String digest = sha256Hex(rawBody);
        UUID parsedPaymentId = payload == null ? null : parsePaymentIdOrNull(payload.paymentId());
        return PaymentEvent.builder()
                .paymentId(parsedPaymentId != null
                        ? parsedPaymentId
                        : UUID.nameUUIDFromBytes(digest.getBytes(StandardCharsets.UTF_8)))
                .provider(provider)
                .providerEventId(providerEventId)
                .type(payload != null && payload.eventType() != null && !payload.eventType().isBlank()
                        ? payload.eventType()
                        : UNKNOWN_TYPE)
                .payload(new String(rawBody, StandardCharsets.UTF_8))
                .status(PaymentEvent.STATUS_FAILED)
                .build();
    }

    private String resolveProviderEventId(WebhookPayload payload, byte[] rawBody) {
        if (payload != null && payload.providerEventId() != null && !payload.providerEventId().isBlank()) {
            return payload.providerEventId();
        }
        String prefix = payload == null ? UNPARSEABLE_PREFIX : INVALID_PREFIX;
        return prefix + sha256Hex(rawBody);
    }

    private UUID parsePaymentId(String paymentId) {
        UUID parsed = parsePaymentIdOrNull(paymentId);
        if (parsed == null) {
            throw BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId);
        }
        return parsed;
    }

    private UUID parsePaymentIdOrNull(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(paymentId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String outboxEventType(PaymentStatus status) {
        return switch (status) {
            case CAPTURED -> "payment.captured.v1";
            case FAILED -> "payment.failed.v1";
            case REFUNDED -> "payment.refunded.v1";
            default -> throw new IllegalStateException("No outbox event type for status " + status);
        };
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
