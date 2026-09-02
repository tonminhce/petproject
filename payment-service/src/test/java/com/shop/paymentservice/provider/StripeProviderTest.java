package com.shop.paymentservice.provider;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentIntentCollection;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * C5 Task 2 — {@link StripeProvider} contract tests against the stripe-java
 * SDK. The SDK's static entry points ({@code PaymentIntent.create},
 * {@code PaymentIntent.list}, {@code Refund.create}) are statically mocked —
 * no network, no Stripe account. Mapping follows the binding
 * {@link PaymentProvider.ProviderResult} port: a created/cached Stripe object
 * is accepted=true (lifecycle completes via webhook), Stripe-side rejection is
 * accepted=false so the service surfaces PAYMENT_PROVIDER_REJECTED.
 */
class StripeProviderTest {

    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String IDEMPOTENCY_KEY = "ord-1-pay-1";
    private static final BigDecimal AMOUNT = new BigDecimal("98.00");

    private MockedStatic<PaymentIntent> paymentIntentStatic;
    private MockedStatic<Refund> refundStatic;

    private StripeProvider provider;

    @BeforeEach
    void setUp() {
        paymentIntentStatic = mockStatic(PaymentIntent.class);
        refundStatic = mockStatic(Refund.class);
        provider = new StripeProvider(new com.shop.paymentservice.config.PaymentStripeProperties(
                "sk_test_123", "whsec_123", "2024-06-20", false));
    }

    @AfterEach
    void tearDown() {
        paymentIntentStatic.close();
        refundStatic.close();
    }

    private PaymentIntent intent(String id, String status) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setStatus(status);
        intent.setClientSecret("pi_secret_client");
        intent.setCurrency("usd");
        return intent;
    }

    @Test
    void captureSucceededIntent_isAccepted_withIntentIdAsProviderEvent() throws Exception {
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_1", "succeeded"));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).isEqualTo("pi_1");
    }

    @Test
    void captureRequiresActionIntent_isAccepted_scaCompletesViaWebhook() throws Exception {
        // D3 — SCA: the intent is created unconfirmed (confirm=false); the
        // client_secret is consumed by Stripe.js on the client (Phase 9), the
        // lifecycle completes when payment_intent.succeeded arrives by webhook.
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_2", "requires_action"));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).isEqualTo("pi_2");

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        paymentIntentStatic.verify(() -> PaymentIntent.create(params.capture(), any(RequestOptions.class)));
        assertThat(params.getValue().getConfirm()).isFalse();
    }

    @Test
    void captureSendsMinorUnitsLowercaseCurrencyAndPaymentMetadata() throws Exception {
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_3", "requires_confirmation"));

        provider.capture(PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
        paymentIntentStatic.verify(() -> PaymentIntent.create(params.capture(), opts.capture()));
        assertThat(params.getValue().getAmount()).isEqualTo(9800L);
        assertThat(params.getValue().getCurrency()).isEqualTo("usd");
        assertThat(params.getValue().getMetadata()).containsEntry("payment_id", PAYMENT_ID.toString());
        assertThat(opts.getValue().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        // Spec D1 — the stripe-java SDK itself pins the Stripe-Version header
        // (Stripe.API_VERSION is final in 24.x); no per-request override exists.
        assertThat(opts.getValue().getApiKey()).isEqualTo("sk_test_123");
    }

    @Test
    void captureVndAmountIsZeroDecimal_noConversion() throws Exception {
        // spec §8 — V1 currency VND is zero-decimal on Stripe: 100000 VND must
        // be sent as 100000 minor units, not 10000000.
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_vnd", "requires_confirmation"));

        provider.capture(PAYMENT_ID, new BigDecimal("100000"), "VND", IDEMPOTENCY_KEY);

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        paymentIntentStatic.verify(() -> PaymentIntent.create(params.capture(), any(RequestOptions.class)));
        assertThat(params.getValue().getAmount()).isEqualTo(100000L);
        assertThat(params.getValue().getCurrency()).isEqualTo("vnd");
    }

    @Test
    void captureIdempotencyConflict_returnsCachedIntentAsAccepted() throws Exception {
        // D2 — a duplicate Idempotency-Key replays the cached original intent;
        // the adapter treats it as success, never an error.
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class)))
                .thenThrow(new IdempotencyException("idempotency conflict", "req_1", "idempotency_error", 400));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).isNotBlank();
    }

    @Test
    void captureCardDecline_mapsToRejectedWithDeclineCode() throws Exception {
        CardException declined = new CardException(
                "card declined", "req_2", "card_declined", "generic_decline",
                "pm_1", "cus_1", 402, null);
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenThrow(declined);

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.providerEventId()).isEqualTo("card_declined");
    }

    @Test
    void captureStripeInfrastructureError_mapsToRejected() throws Exception {
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("network down"));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.providerEventId()).startsWith("stripe-error-");
    }

    @Test
    void refundSucceeds_withRefundIdempotencyKeyDerivedFromPaymentKey() throws Exception {
        PaymentIntent original = intent("pi_orig", "succeeded");
        PaymentIntentCollection matches = new PaymentIntentCollection();
        matches.setData(List.of(original));
        paymentIntentStatic.when(() -> PaymentIntent.list(any(PaymentIntentListParams.class),
                any(RequestOptions.class))).thenReturn(matches);

        Refund created = new Refund();
        created.setId("re_1");
        created.setStatus("succeeded");
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(created);

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerEventId()).isEqualTo("re_1");

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
        refundStatic.verify(() -> Refund.create(params.capture(), opts.capture()));
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_orig");
        assertThat(params.getValue().getAmount()).isEqualTo(9800L);
        assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("refund-" + IDEMPOTENCY_KEY + "-98.00");
    }

    @Test
    void refundWithoutMatchingIntent_mapsToRejected() throws Exception {
        PaymentIntentCollection noMatches = new PaymentIntentCollection();
        noMatches.setData(List.of());
        paymentIntentStatic.when(() -> PaymentIntent.list(any(PaymentIntentListParams.class),
                any(RequestOptions.class))).thenReturn(noMatches);

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        refundStatic.verifyNoInteractions();
    }

    @Test
    void refundStripeError_mapsToRejected() throws Exception {
        PaymentIntent original = intent("pi_orig", "succeeded");
        PaymentIntentCollection matches = new PaymentIntentCollection();
        matches.setData(List.of(original));
        paymentIntentStatic.when(() -> PaymentIntent.list(any(PaymentIntentListParams.class),
                any(RequestOptions.class))).thenReturn(matches);
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("network down"));

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
    }

    @Test
    void blankSecretKey_failsFastAtBeanCreation() {
        // D10 — provider=stripe with no credentials must never boot.
        com.shop.paymentservice.config.PaymentStripeProperties blank =
                new com.shop.paymentservice.config.PaymentStripeProperties("", "whsec", "2024-06-20", false);
        assertThatThrownBy(() -> new StripeProvider(blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key");
    }

    @Test
    void nameIsStripe() {
        assertThat(provider.name()).isEqualTo("stripe");
    }
}
