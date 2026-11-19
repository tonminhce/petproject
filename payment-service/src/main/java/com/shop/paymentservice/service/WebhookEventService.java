package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.config.PaymentStripeProperties;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.provider.StripeCurrencyUnits;import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.webhook.WebhookPayload;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook entry + retry. Before this fix, events were marked FAILED-from-birth
 * and the dedup-on-exists check swallowed every provider retry of the same
 * {@code provider_event_id}. The state machine now distinguishes:
 * <ul>
 *   <li>PROCESSED — terminal success, dedup-skip on retried delivery</li>
 *   <li>FAILED_RETRYABLE — initial state on parse/handler failure; picked up by
 *       {@link com.shop.paymentservice.scheduler.WebhookRetryScheduler}</li>
 *   <li>FAILED_PERMANENT — exhausted retries; needs ops attention</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventService {

    static final String UNPARSEABLE_PREFIX = "unparseable-";
    static final String INVALID_PREFIX = "invalid-";
    static final String UNKNOWN_TYPE = "unknown";

    /** The stripe webhook path provider label (mirrors {@code {provider}=stripe}). */
    static final String STRIPE_PROVIDER = "stripe";

    private static final Map<String, String> STRIPE_EVENT_STATUS = Map.of(
            "payment_intent.succeeded", "CAPTURED",
            "payment_intent.payment_failed", "FAILED",
            "charge.refunded", "REFUNDED");

    /** The Stripe event type that carries a refund. */
    private static final String REFUND_EVENT_TYPE = "charge.refunded";

    /** Carrier status used for a refund that has not returned the full amount. */
    private static final String PARTIAL_REFUND_STATUS = "PARTIALLY_REFUNDED";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository eventRepository;
    private final PaymentWriter writer;
    private final ObjectMapper objectMapper;
    private final ReceiptService receiptService;
    private final PaymentStripeProperties stripeProperties;

    public void handleStripe(byte[] rawBody, String signatureHeader) {
        String webhookSecret = stripeProperties == null ? null : stripeProperties.webhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()
                || signatureHeader == null || signatureHeader.isBlank()) {
            // Fail-closed: an unconfigured secret must behave exactly like a
            // bad signature, never like an open receiver.
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        Event event;
        try {
            event = Webhook.constructEvent(new String(rawBody, StandardCharsets.UTF_8),
                    signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        } catch (RuntimeException e) {
            // Fail-closed: constructEvent also raises unchecked Gson errors on
            // JSON-corrupting tampering — an unparseable body can never be a
            // treatment (401 PAY-5005, no state change), never a 500.
            log.warn("Stripe webhook payload rejected during verification: {}", e.getMessage());
            throw BusinessException.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        String stripeType = event.getType();
        String mappedStatus = STRIPE_EVENT_STATUS.get(stripeType);
        if (mappedStatus == null) {
            storeProcessedNoOp(event.getId(), stripeType, rawBody);
            return;
        }
        handle(STRIPE_PROVIDER, toCarrierPayload(event.getId(), stripeType, mappedStatus, rawBody));
    }

    public void handle(String provider, byte[] rawBody) {
        WebhookPayload payload = tryParse(rawBody);
        String providerEventId = resolveProviderEventId(payload, rawBody);

        //   FAILED_RETRYABLE   → fall through; the retry scheduler will pick it up
        //                        status transitions to PROCESSED; if it fails we
        Optional<PaymentEvent> existing = eventRepository
                .findFirstByProviderAndProviderEventId(provider, providerEventId);
        if (existing.isPresent() && PaymentEvent.STATUS_PROCESSED.equals(existing.get().getStatus())) {
            return;
        }
        if (existing.isEmpty()) {
            PaymentEvent event = buildEvent(provider, providerEventId, payload, rawBody);
            event.setStatus(PaymentEvent.STATUS_FAILED_RETRYABLE);
            event.setNextRetryAt(Instant.now());
            try {
                writer.insertEvent(event);
            } catch (DataIntegrityViolationException e) {
                log.debug("Webhook event already stored (provider={}, providerEventId={})", provider, providerEventId);
                return;
            }
        }

        if (payload == null || !hasCriticalFields(payload)) {
            // retry scheduler will give up after MAX attempts and surface it.
            return;
        }
        processFromRaw(provider, providerEventId, rawBody);
    }

    /**
     * Public entry point for {@link com.shop.paymentservice.scheduler.WebhookRetryScheduler}.
     * Re-parses the persisted payload and runs the same handler logic as a fresh
     * delivery. Idempotent: state-machine rejects illegal transitions, and
     * {@link #completeWithEvent} is idempotent for already-PROCESSED rows.
     */
    public void retry(PaymentEvent event) {
        byte[] rawBody = event.getPayload() == null ? new byte[0] : event.getPayload().getBytes(StandardCharsets.UTF_8);
        WebhookPayload payload = tryParse(rawBody);
        if (payload == null || !hasCriticalFields(payload)) {
            event.setStatus(PaymentEvent.STATUS_FAILED_PERMANENT);
            event.setLastError("payload missing critical fields on retry");
            writer.saveEvent(event);
            return;
        }
        try {
            process(payload, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void processFromRaw(String provider, String providerEventId, byte[] rawBody) {
        WebhookPayload payload = tryParse(rawBody);
        if (payload == null || !hasCriticalFields(payload)) {
            return;
        }
        // can transition it the same way it would on retry. If the read races
        // a concurrent retry (extremely unlikely), we fall through to inline
        PaymentEvent event = eventRepository
                .findFirstByProviderAndProviderEventId(provider, providerEventId)
                .orElse(null);
        if (event == null) {
            return;
        }
        try {
            process(payload, event);
        } catch (Exception e) {
            log.error("Webhook processing failed (provider={}, providerEventId={})", provider, providerEventId, e);
            markFailedRetryable(event, e.getMessage());
        }
    }

    /**
     * Extracted from the original handle(); now {@code public} so the retry
     * scheduler can invoke it on a re-loaded event row. The state-machine
     * transition rules live here and nowhere else.
     */
    public void process(WebhookPayload payload, PaymentEvent event) {
        UUID paymentId = parsePaymentId(payload.paymentId());
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId));
        PaymentStatus target = parseStatus(payload.status());
        if (target == null) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, payment.getStatus(), payload.status());
        }
        if (target == PaymentStatus.PARTIALLY_REFUNDED) {
            applyPartialRefund(payload, payment, event);
            return;
        }
        if (payload.amount() == null || payment.getAmount() == null
                || payload.amount().compareTo(payment.getAmount()) != 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, payload.amount(), payment.getAmount());
        }
        PaymentStatus next = PaymentStateMachine.transition(payment.getStatus(), target);
        payment.setPreviousStatus(payment.getStatus());
        payment.setStatus(next);
        if (next == PaymentStatus.REFUNDED) {
            BigDecimal already = payment.getRefundedAmount() == null
                    ? BigDecimal.ZERO : payment.getRefundedAmount();
            payment.setRefundedAmount(payment.getAmount());
            if (already.compareTo(payment.getAmount()) > 0) {
                log.warn("Payment {} refunded amount {} exceeds the captured amount {}",
                        payment.getId(), already, payment.getAmount());
            }
        }
        Payment saved = writer.completeWithEvent(payment, event, outboxEventType(next));
        if (next == PaymentStatus.CAPTURED) {
            attachReceipt(saved);
        }
    }

    private void applyPartialRefund(WebhookPayload payload, Payment payment, PaymentEvent event) {
        BigDecimal already = payment.getRefundedAmount() == null
                ? BigDecimal.ZERO : payment.getRefundedAmount();
        BigDecimal remaining = payment.getAmount() == null
                ? BigDecimal.ZERO : payment.getAmount().subtract(already);
        if (payload.amount() == null || payload.amount().signum() <= 0
                || payload.amount().compareTo(remaining) > 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, payload.amount(), remaining);
        }
        PaymentStatus next = PaymentStateMachine.transition(payment.getStatus(),
                PaymentStatus.PARTIALLY_REFUNDED);
        payment.setPreviousStatus(payment.getStatus());
        payment.setStatus(next);
        payment.setRefundedAmount(already.add(payload.amount()));
        writer.completeWithEvent(payment, event, outboxEventType(next));
        log.info("Partial refund applied to payment {}: {} of {} already refunded, {} returned now",
                payment.getId(), already, payment.getAmount(), payload.amount());
    }

    private byte[] toCarrierPayload(String eventId, String stripeType, String mappedStatus, byte[] rawBody) {
        try {
            JsonNode obj = objectMapper.readTree(rawBody).path("data").path("object");
            String paymentId = obj.path("metadata").path("payment_id").asText(null);
            String currency = obj.path("currency").asText(null);
            Long amountMinor = obj.path("amount").isNumber() ? obj.path("amount").asLong() : null;
            BigDecimal amount = StripeCurrencyUnits.fromMinor(amountMinor, currency);
            String status = mappedStatus;
            if (REFUND_EVENT_TYPE.equals(stripeType)) {

                // 'amount' is the ORIGINAL charge amount and 'amount_refunded'
                // is how much has actually come back. The refunded total is the
                // amount that must be reconciled against the payment, so it is
                // the one carried on the carrier payload; the charge amount is
                // only used to tell a full refund from a partial one.
                Long refundedMinor = obj.path("amount_refunded").isNumber()
                        ? obj.path("amount_refunded").asLong() : null;
                if (refundedMinor != null) {
                    amount = StripeCurrencyUnits.fromMinor(refundedMinor, currency);
                    if (amountMinor != null && refundedMinor < amountMinor) {
                        status = PARTIAL_REFUND_STATUS;
                    }
                }
            }
            WebhookPayload payload = WebhookPayload.builder()
                    .eventId(eventId)
                    .eventType(stripeType)
                    .paymentId(paymentId)
                    .amount(amount)
                    .currency(currency == null ? null : currency.toUpperCase(Locale.ROOT))
                    .status(status)
                    .providerEventId(eventId)
                    .build();
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            // synthetic non-JSON body embeds the stable event id so dedupe
            log.warn("Stripe event {} could not be converted to the carrier payload", eventId, e);
            return ("not-json-" + eventId).getBytes(StandardCharsets.UTF_8);
        }
    }

    private void storeProcessedNoOp(String eventId, String stripeType, byte[] rawBody) {
        PaymentEvent event = buildEvent(STRIPE_PROVIDER, eventId, null, rawBody);
        event.setType(stripeType);
        event.setStatus(PaymentEvent.STATUS_PROCESSED);
        event.setLastError("ignored stripe event type (not mapped to payment lifecycle)");
        try {
            writer.insertEvent(event);
        } catch (DataIntegrityViolationException e) {
            log.debug("Ignored stripe event already stored (eventId={}, type={})", eventId, stripeType);
        }
    }

    private void markFailedRetryable(PaymentEvent event, String error) {
        event.setStatus(PaymentEvent.STATUS_FAILED_RETRYABLE);
        event.setLastError(truncate(error, 1024));
        writer.saveEvent(event);
    }

    private void attachReceipt(Payment payment) {
        String receiptKey = receiptService.storeReceipt(payment);
        if (receiptKey != null) {
            payment.setReceiptKey(receiptKey);
            writer.saveAndFlush(payment);
        }
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

    /**
     * Whitelist parsing replaces {@code PaymentStatus.valueOf(payload.status())}.
     * A typo'd carrier payload ("capture") previously surfaced as IllegalArgumentException,
     * caught upstream only in narrow contexts — explicit whitelist avoids leaking enum
     * semantics to untrusted input.
     */
    private PaymentStatus parseStatus(String status) {
        return switch (status == null ? "" : status) {
            case "CAPTURED" -> PaymentStatus.CAPTURED;
            case "PARTIALLY_REFUNDED" -> PaymentStatus.PARTIALLY_REFUNDED;
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
                .status(PaymentEvent.STATUS_FAILED_RETRYABLE)
                .retryCount(0)
                .nextRetryAt(Instant.now())
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
            case PARTIALLY_REFUNDED -> "payment.partially_refunded.v1";
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
