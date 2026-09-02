package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.config.PaymentStripeProperties;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.provider.StripeCurrencyUnits;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.webhook.WebhookPayload;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * C3 — webhook entry + retry. Before this fix, events were marked FAILED-from-birth
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

    /**
     * C5 Task 3 (spec D4) — Stripe event types mapped onto the payment
 * lifecycle. Exactly these three; everything else Stripe emits is
     * deliberately-ignored (stored PROCESSED so dedupe swallows retries).
     */
    private static final Map<String, String> STRIPE_EVENT_STATUS = Map.of(
            "payment_intent.succeeded", "CAPTURED",
            "payment_intent.payment_failed", "FAILED",
            "charge.refunded", "REFUNDED");

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository eventRepository;
    private final PaymentWriter writer;
    private final ObjectMapper objectMapper;
    private final ReceiptService receiptService;
    private final PaymentStripeProperties stripeProperties;

    /**
     * C5 Task 3 — Stripe webhook entry ({@code POST /api/v1/webhooks/payments/stripe}).
     * Signature verification is delegated to stripe-java's
     * {@link Webhook#constructEvent} (spec D4 — NEVER hand-rolled HMAC);
     * {@code SignatureVerificationException} → 401 PAY-5005 with no state
     * change. A verified event is converted into the fleet carrier payload
     * (minor units → major) and delegated to {@link #handle(String, byte[])}
     * so dedupe, the state machine, the amount check, the outbox row and
     * receipts reuse the existing C3 machinery untouched.
     */
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
            // legitimate delivery, so it gets exactly the bad-signature
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

        // C3 — dedup decision:
        //   PROCESSED          → silent skip (we already handled this id)
        //   FAILED_RETRYABLE   → fall through; the retry scheduler will pick it up
        //   absent             → insert as FAILED_RETRYABLE with nextRetryAt=now,
        //                        then process in-line. If process succeeds the
        //                        status transitions to PROCESSED; if it fails we
        //                        stay FAILED_RETRYABLE and the scheduler retries.
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
            // No useful fields to process — event stays FAILED_RETRYABLE; the
            // retry scheduler will give up after MAX attempts and surface it.
            return;
        }
        processFromRaw(provider, providerEventId, rawBody);
    }

    /**
     * C3 — public entry point for {@link com.shop.paymentservice.scheduler.WebhookRetryScheduler}.
     * Re-parses the persisted payload and runs the same handler logic as a fresh
     * delivery. Idempotent: state-machine rejects illegal transitions, and
     * {@link #completeWithEvent} is idempotent for already-PROCESSED rows.
     */
    public void retry(PaymentEvent event) {
        byte[] rawBody = event.getPayload() == null ? new byte[0] : event.getPayload().getBytes(StandardCharsets.UTF_8);
        WebhookPayload payload = tryParse(rawBody);
        if (payload == null || !hasCriticalFields(payload)) {
            // Unrecoverable — terminal failure for this attempt.
            event.setStatus(PaymentEvent.STATUS_FAILED_PERMANENT);
            event.setLastError("payload missing critical fields on retry");
            writer.saveEvent(event);
            return;
        }
        try {
            process(payload, event);
            // Success path: process() set status=PROCESSED via writer.completeWithEvent.
        } catch (Exception e) {
            // Caller (scheduler) will increment retry_count and decide next_retry_at.
            throw new RuntimeException(e);
        }
    }

    private void processFromRaw(String provider, String providerEventId, byte[] rawBody) {
        WebhookPayload payload = tryParse(rawBody);
        if (payload == null || !hasCriticalFields(payload)) {
            return;
        }
        // For the first attempt we re-read the just-inserted event so process()
        // can transition it the same way it would on retry. If the read races
        // a concurrent retry (extremely unlikely), we fall through to inline
        // processing by loading by (provider, providerEventId) once more.
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
     * C3 — extracted from the original handle(); now {@code public} so the retry
     * scheduler can invoke it on a re-loaded event row. The state-machine
     * transition rules live here and nowhere else.
     */
    public void process(WebhookPayload payload, PaymentEvent event) {
        UUID paymentId = parsePaymentId(payload.paymentId());
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, paymentId));
        if (payload.amount() == null || payment.getAmount() == null
                || payload.amount().compareTo(payment.getAmount()) != 0) {
            throw BusinessException.of(ErrorCode.AMOUNT_MISMATCH, payload.amount(), payment.getAmount());
        }
        PaymentStatus target = parseStatus(payload.status());
        if (target == null) {
            throw BusinessException.of(ErrorCode.PAYMENT_INVALID_STATE, payment.getStatus(), payload.status());
        }
        PaymentStatus next = PaymentStateMachine.transition(payment.getStatus(), target);
        payment.setPreviousStatus(payment.getStatus());
        payment.setStatus(next);
        Payment saved = writer.completeWithEvent(payment, event, outboxEventType(next));
        if (next == PaymentStatus.CAPTURED) {
            attachReceipt(saved);
        }
    }

    /**
     * C5 Task 3 — convert a signature-verified Stripe event into the fleet
     * carrier payload: payment id comes from {@code data.object.metadata}
     * (set by StripeProvider at intent creation; Stripe copies PaymentIntent
     * metadata onto its charges), amount goes minor → major units so the
     * existing {@code process()} amount check compares like with like.
     */
    private byte[] toCarrierPayload(String eventId, String stripeType, String mappedStatus, byte[] rawBody) {
        try {
            JsonNode obj = objectMapper.readTree(rawBody).path("data").path("object");
            String paymentId = obj.path("metadata").path("payment_id").asText(null);
            String currency = obj.path("currency").asText(null);
            Long amountMinor = obj.path("amount").isNumber() ? obj.path("amount").asLong() : null;
            WebhookPayload payload = WebhookPayload.builder()
                    .eventId(eventId)
                    .eventType(stripeType)
                    .paymentId(paymentId)
                    .amount(StripeCurrencyUnits.fromMinor(amountMinor, currency))
                    .currency(currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT))
                    .status(mappedStatus)
                    .providerEventId(eventId)
                    .build();
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            // Signature was valid but the body shape defeated extraction —
            // surface as an unparseable carrier delivery; the generic handle()
            // path stores it FAILED_RETRYABLE for the scheduler to flag. The
            // synthetic non-JSON body embeds the stable event id so dedupe
            // stays deterministic across Stripe's retries.
            log.warn("Stripe event {} could not be converted to the carrier payload", eventId, e);
            return ("not-json-" + eventId).getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * C5 Task 3 — deliberately-ignored Stripe types (e.g. charge.succeeded
     * companion events). Stored PROCESSED keyed by the Stripe event id so
     * dedupe swallows Stripe's delivery retries without the retry scheduler
     * flagging them FAILED_PERMANENT for ops.
     */
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
     * C3 — whitelist parsing replaces {@code PaymentStatus.valueOf(payload.status())}.
     * A typo'd carrier payload ("capture") previously surfaced as IllegalArgumentException,
     * caught upstream only in narrow contexts — explicit whitelist avoids leaking enum
     * semantics to untrusted input.
     */
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
