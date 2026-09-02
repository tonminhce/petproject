package com.shop.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.paymentservice.config.PaymentStripeProperties;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.outbox.PaymentEventPublisher;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C5 Task 3 — Stripe webhook branch of {@link WebhookEventService}. Signature
 * verification MUST go through stripe-java's {@link Webhook#constructEvent}
 * (spec D4 — never hand-rolled HMAC). Test signatures are generated locally
 * with the SDK's own {@link Webhook.Util#computeHmacSha256} over
 * {@code t + "." + payload} — no network, no Stripe account.
 *
 * <p>Event mapping (spec Task 3): payment_intent.succeeded → CAPTURED,
 * payment_intent.payment_failed → FAILED, charge.refunded → REFUNDED. The
 * verified event is converted into the fleet carrier payload (amounts back to
 * major units) so dedupe, state machine, amount check, outbox and receipt all
 * reuse the existing C3 machinery.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookEventServiceStripeTest {

    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final BigDecimal AMOUNT = new BigDecimal("98.00");
    private static final String STRIPE_WEBHOOK_SECRET = "whsec_test_stripe";

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentEventRepository eventRepository;
    @Mock PaymentWriter writer;
    @Mock PaymentEventPublisher publisher;
    @Mock ReceiptService receiptService;

    private WebhookEventService service;

    @BeforeEach
    void setUp() {
        service = new WebhookEventService(paymentRepository, eventRepository, writer, new ObjectMapper(),
                receiptService, new PaymentStripeProperties("", STRIPE_WEBHOOK_SECRET, "2024-06-20", false));
        lenient().doAnswer(inv -> {
            inv.getArgument(1, PaymentEvent.class).setStatus(PaymentEvent.STATUS_PROCESSED);
            return inv.getArgument(0, Payment.class);
        }).when(writer).completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
    }

    private Payment payment(PaymentStatus status) {
        return Payment.builder()
                .id(PAYMENT_ID)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .currency("USD")
                .status(status)
                .provider("stripe")
                .idempotencyKey("ord-1-pay-1")
                .build();
    }

    /**
     * A payment_intent-shaped Stripe event body. {@code amountMinor} is what
     * Stripe puts on the wire (minor units); the service must convert.
     */
    private byte[] stripeEventBody(String eventId, String type, long amountMinor, String currency) {
        String body = """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2023-10-16",
                  "type": "%s",
                  "data": {
                    "object": {
                      "object": "payment_intent",
                      "id": "pi_test_1",
                      "amount": %d,
                      "currency": "%s",
                      "status": "succeeded",
                      "metadata": {
                        "payment_id": "%s",
                        "idempotency_key": "ord-1-pay-1"
                      }
                    }
                  }
                }
                """.formatted(eventId, type, amountMinor, currency, PAYMENT_ID);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** charge.refunded — object is a Charge (PaymentIntent metadata is copied onto charges). */
    private byte[] chargeRefundedBody(String eventId, long amountMinor, String currency) {
        String body = """
                {
                  "id": "%s",
                  "object": "event",
                  "type": "charge.refunded",
                  "data": {
                    "object": {
                      "object": "charge",
                      "id": "ch_test_1",
                      "amount": %d,
                      "currency": "%s",
                      "payment_intent": "pi_test_1",
                      "metadata": {
                        "payment_id": "%s"
                      }
                    }
                  }
                }
                """.formatted(eventId, amountMinor, currency, PAYMENT_ID);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * C5 Task 3 — locally-signed Stripe-Signature header using the SDK's own
     * HMAC util (t + "." + payload, hex v1) — the exact scheme
     * {@code Webhook.constructEvent} verifies.
     */
    private String signedHeader(byte[] rawBody, String secret) {
        long t = Webhook.Util.getTimeNow();
        String signedPayload = t + "." + new String(rawBody, StandardCharsets.UTF_8);
        try {
            // SDK util arg order: (secret, signedPayload) — mirrors what
            // Webhook.Signature computes during verification.
            String v1 = Webhook.Util.computeHmacSha256(secret, signedPayload);
            return "t=" + t + ",v1=" + v1;
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private void happyPathStubs(String eventId) {
        when(eventRepository.findFirstByProviderAndProviderEventId("stripe", eventId))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(storedEvent(eventId)));
        AtomicReference<PaymentEvent> stored = new AtomicReference<>();
        lenient().doAnswer(inv -> {
            stored.set(inv.getArgument(0, PaymentEvent.class));
            return inv.getArgument(0, PaymentEvent.class);
        }).when(writer).insertEvent(any(PaymentEvent.class));
    }

    private PaymentEvent storedEvent(String eventId) {
        return PaymentEvent.builder()
                .paymentId(PAYMENT_ID)
                .provider("stripe")
                .providerEventId(eventId)
                .type("payment_intent.succeeded")
                .payload("{}")
                .status(PaymentEvent.STATUS_FAILED_RETRYABLE)
                .retryCount(0)
                .build();
    }

    @Test
    void validSignature_paymentIntentSucceeded_transitionsPaymentToCaptured() {
        byte[] raw = stripeEventBody("evt_ok_1", "payment_intent.succeeded", 9800, "usd");
        happyPathStubs("evt_ok_1");
        Payment pending = payment(PaymentStatus.PENDING);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));

        service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).completeWithEvent(paymentCaptor.capture(), any(PaymentEvent.class), eq("payment.captured.v1"));
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paymentCaptor.getValue().getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void validSignature_paymentIntentSucceeded_minorUnitsConvertedBackToMajorForAmountCheck() {
        // Stripe wires 9800 (minor units); the local row holds 98.00 (major).
        // No conversion → AMOUNT_MISMATCH would kill every legitimate capture.
        byte[] raw = stripeEventBody("evt_ok_scale", "payment_intent.succeeded", 9800, "usd");
        happyPathStubs("evt_ok_scale");
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThatCode(() -> service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET)))
                .doesNotThrowAnyException();

        verify(writer).completeWithEvent(any(Payment.class), any(PaymentEvent.class), eq("payment.captured.v1"));
    }

    @Test
    void validSignature_paymentIntentFailed_transitionsPaymentToFailed() {
        byte[] raw = stripeEventBody("evt_fail_1", "payment_intent.payment_failed", 9800, "usd");
        happyPathStubs("evt_fail_1");
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).completeWithEvent(paymentCaptor.capture(), any(PaymentEvent.class), eq("payment.failed.v1"));
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void validSignature_chargeRefunded_transitionsPaymentToRefunded() {
        byte[] raw = chargeRefundedBody("evt_ref_1", 9800, "usd");
        happyPathStubs("evt_ref_1");
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.CAPTURED)));

        service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(writer).completeWithEvent(paymentCaptor.capture(), any(PaymentEvent.class), eq("payment.refunded.v1"));
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void invalidSignature_throwsPay5005_noStateChange() {
        byte[] raw = stripeEventBody("evt_bad_1", "payment_intent.succeeded", 9800, "usd");
        String forged = signedHeader(raw, "whsec_attacker_knows_only_this");

        assertThatThrownBy(() -> service.handleStripe(raw, forged))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode());
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(writer);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void tamperedBody_validSignatureOverDifferentPayload_throwsPay5005() {
        byte[] signed = stripeEventBody("evt_tamper", "payment_intent.succeeded", 9800, "usd");
        byte[] tampered = stripeEventBody("evt_tamper", "payment_intent.succeeded", 1, "usd");

        assertThatThrownBy(() -> service.handleStripe(tampered, signedHeader(signed, STRIPE_WEBHOOK_SECRET)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode());
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(writer);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void missingSignatureHeader_throwsPay5005() {
        byte[] raw = stripeEventBody("evt_nosig", "payment_intent.succeeded", 9800, "usd");

        assertThatThrownBy(() -> service.handleStripe(raw, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode());
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(writer);
    }

    @Test
    void jsonCorruptingTampering_failsClosedAsPay5005_never500() {
        // Webhook.constructEvent raises UNCHECKED Gson errors on unparseable
        // bodies (C5 Task 5 IT finding). Fail-closed: identical to a bad
        // signature — 401 PAY-5005, never a 500.
        byte[] garbage = "this is not json at all {{{".getBytes(StandardCharsets.UTF_8);
        String header = signedHeader(garbage, STRIPE_WEBHOOK_SECRET);
        byte[] tampered = java.util.Arrays.copyOf(garbage, garbage.length + 1);
        tampered[tampered.length - 1] = '!'; // signature was over different bytes anyway

        assertThatThrownBy(() -> service.handleStripe(tampered, header))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode());
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(writer);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void blankWebhookSecret_failsClosed_throwsPay5005() {
        WebhookEventService unconfigured = new WebhookEventService(paymentRepository, eventRepository, writer,
                new ObjectMapper(), receiptService,
                new PaymentStripeProperties("sk_test_1", "", "2024-06-20", false));
        byte[] raw = stripeEventBody("evt_nosecret", "payment_intent.succeeded", 9800, "usd");

        assertThatThrownBy(() -> unconfigured.handleStripe(raw, signedHeader(raw, "anything")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getCode());
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
                });
        verifyNoInteractions(writer);
    }

    @Test
    void duplicateAlreadyProcessedEvent_acksNoOp() {
        byte[] raw = stripeEventBody("evt_dupe", "payment_intent.succeeded", 9800, "usd");
        PaymentEvent processed = PaymentEvent.builder()
                .paymentId(PAYMENT_ID)
                .provider("stripe")
                .providerEventId("evt_dupe")
                .type("payment_intent.succeeded")
                .payload("{}")
                .status(PaymentEvent.STATUS_PROCESSED)
                .retryCount(1)
                .build();
        when(eventRepository.findFirstByProviderAndProviderEventId("stripe", "evt_dupe"))
                .thenReturn(Optional.of(processed));

        assertThatCode(() -> service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET)))
                .doesNotThrowAnyException();

        verifyNoInteractions(writer);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void unmappedStripeType_storedProcessed_noTransitionNoOpsNoise() {
        // Companion events (charge.succeeded, checkout.session.completed…) are
        // deliberately out of scope (spec Task 3 maps exactly 3 types) — store
        // PROCESSED so dedupe swallows Stripe's retries without the retry
        // scheduler flagging them FAILED_PERMANENT for ops.
        byte[] raw = stripeEventBody("evt_unmapped", "charge.succeeded", 9800, "usd");

        assertThatCode(() -> service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET)))
                .doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).insertEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_PROCESSED);
        assertThat(eventCaptor.getValue().getType()).isEqualTo("charge.succeeded");
        assertThat(eventCaptor.getValue().getProviderEventId()).isEqualTo("evt_unmapped");
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void succeededEvent_amountMismatchAgainstLocalRow_marksEventFailedRetryable() {
        byte[] raw = stripeEventBody("evt_mismatch", "payment_intent.succeeded", 9999, "usd");
        happyPathStubs("evt_mismatch");
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        assertThatCode(() -> service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET)))
                .doesNotThrowAnyException();

        ArgumentCaptor<PaymentEvent> saveCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(writer).saveEvent(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        verify(writer, org.mockito.Mockito.never())
                .completeWithEvent(any(Payment.class), any(PaymentEvent.class), anyString());
    }

    @Test
    void vndSucceededEvent_zeroDecimalAmountsMatchDirectly() {
        // VND is zero-decimal on Stripe (spec §8): 100000 minor == 100000 major.
        byte[] raw = stripeEventBody("evt_vnd", "payment_intent.succeeded", 100000, "vnd");
        happyPathStubs("evt_vnd");
        Payment vndPayment = Payment.builder()
                .id(PAYMENT_ID)
                .orderId(ORDER_ID)
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .status(PaymentStatus.PENDING)
                .provider("stripe")
                .idempotencyKey("ord-1-pay-1")
                .build();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(vndPayment));

        assertThatCode(() -> service.handleStripe(raw, signedHeader(raw, STRIPE_WEBHOOK_SECRET)))
                .doesNotThrowAnyException();

        verify(writer).completeWithEvent(any(Payment.class), any(PaymentEvent.class), eq("payment.captured.v1"));
    }
}
