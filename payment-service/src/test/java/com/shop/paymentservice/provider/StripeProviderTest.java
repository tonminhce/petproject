package com.shop.paymentservice.provider;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentIntentSearchResult;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

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
        provider = new StripeProvider(com.shop.paymentservice.config.PaymentStripeProperties.of(
                "sk_test_123", "whsec_123", "2024-06-20", "", false));
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
        intent.setAmount(9800L);
        return intent;
    }

    @Test
    void captureSucceededIntent_isConfirmed_withIntentIdAsProviderEvent() throws Exception {
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_1", "succeeded"));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.CONFIRMED);
        assertThat(result.providerEventId()).isEqualTo("pi_1");
    }

    @Test
    void captureSucceededIntentWithAnotherAmount_isRejected() throws Exception {
        PaymentIntent mismatched = intent("pi_amount", "succeeded");
        mismatched.setAmount(5000L);
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(mismatched);

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("amount-mismatch");
    }

    @Test
    void captureWithMismatchedCurrency_isRejected() throws Exception {
        PaymentIntent mismatched = intent("pi_currency", "succeeded");
        mismatched.setCurrency("vnd");
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(mismatched);

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("currency-mismatch");
    }

    @Test
    void captureRequiresActionIntent_requiresClientConfirmation_andCarriesTheClientSecret() throws Exception {

        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_2", "requires_action"));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REQUIRES_CONFIRMATION);
        assertThat(result.providerEventId()).isEqualTo("pi_2");
        assertThat(result.clientSecret()).isEqualTo("pi_secret_client");

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
        assertThat(opts.getValue().getApiKey()).isEqualTo("sk_test_123");
    }

    @Test
    void captureVndAmountIsZeroDecimal_noConversion() throws Exception {
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class))).thenReturn(intent("pi_vnd", "requires_confirmation"));

        provider.capture(PAYMENT_ID, new BigDecimal("100000"), "VND", IDEMPOTENCY_KEY);

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        paymentIntentStatic.verify(() -> PaymentIntent.create(params.capture(), any(RequestOptions.class)));
        assertThat(params.getValue().getAmount()).isEqualTo(100000L);
        assertThat(params.getValue().getCurrency()).isEqualTo("vnd");
    }

    @Test
    void captureIdempotencyConflict_isIndeterminate_andCarriesNoFabricatedIntentId() throws Exception {

        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class)))
                .thenThrow(new IdempotencyException("idempotency conflict", "req_1", "idempotency_error", 400));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("idempotency-conflict");
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
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("card_declined");
    }

    @Test
    void captureStripeTimeout_isIndeterminate_notRejected() throws Exception {
        paymentIntentStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),
                any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("network down"));

        PaymentProvider.ProviderResult result = provider.capture(
                PAYMENT_ID, AMOUNT, "USD", IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).startsWith("stripe-error-");
    }

    @Test
    void refundSucceeds_withRefundIdempotencyKeyDerivedFromPaymentKey() throws Exception {
        PaymentIntent original = intent("pi_orig", "succeeded");
        PaymentIntentSearchResult matches = new PaymentIntentSearchResult();
        matches.setData(List.of(original));
        paymentIntentStatic.when(() -> PaymentIntent.search(any(PaymentIntentSearchParams.class),
                any(RequestOptions.class))).thenReturn(matches);

        Refund created = new Refund();
        created.setId("re_1");
        created.setStatus("succeeded");
        created.setAmount(9800L);
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(created);

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.CONFIRMED);
        assertThat(result.providerEventId()).isEqualTo("re_1");

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
        refundStatic.verify(() -> Refund.create(params.capture(), opts.capture()));
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_orig");
        assertThat(params.getValue().getAmount()).isEqualTo(9800L);
        assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("refund-" + IDEMPOTENCY_KEY + "-98.00");
    }

    @Test
    void refundUsesTheStoredProviderIntentId_withoutSearch() throws Exception {
        PaymentIntent stored = intent("pi_stored", "succeeded");
        paymentIntentStatic.when(() -> PaymentIntent.retrieve(eq("pi_stored"), any(RequestOptions.class)))
                .thenReturn(stored);

        Refund created = new Refund();
        created.setId("re_stored");
        created.setStatus("succeeded");
        created.setAmount(9800L);
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(created);

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY, "pi_stored");

        assertThat(result.accepted()).isTrue();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.CONFIRMED);
        assertThat(result.providerEventId()).isEqualTo("re_stored");

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        refundStatic.verify(() -> Refund.create(params.capture(), any(RequestOptions.class)));
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_stored");
        paymentIntentStatic.verify(() -> PaymentIntent.search(any(PaymentIntentSearchParams.class),
                any(RequestOptions.class)), never());
    }

    @Test
    void refundWithMismatchedCurrency_isRejected() throws Exception {
        PaymentIntent original = intent("pi_currency", "succeeded");
        original.setCurrency("usd");
        PaymentIntentSearchResult matches = new PaymentIntentSearchResult();
        matches.setData(List.of(original));
        paymentIntentStatic.when(() -> PaymentIntent.search(any(PaymentIntentSearchParams.class),
                any(RequestOptions.class))).thenReturn(matches);

        Refund created = new Refund();
        created.setId("re_currency");
        created.setStatus("succeeded");
        created.setAmount(9800L);
        created.setCurrency("vnd");
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(created);

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        assertThat(result.providerEventId()).isEqualTo("currency-mismatch");
    }

    @Test
    void refundWithoutMatchingIntent_mapsToRejected() throws Exception {
        PaymentIntentSearchResult noMatches = new PaymentIntentSearchResult();
        noMatches.setData(List.of());
        paymentIntentStatic.when(() -> PaymentIntent.search(any(PaymentIntentSearchParams.class),
                any(RequestOptions.class))).thenReturn(noMatches);

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REJECTED);
        refundStatic.verifyNoInteractions();
    }

    @Test
    void refundStripeTimeout_isIndeterminate_notRejected() throws Exception {
        PaymentIntent original = intent("pi_orig", "succeeded");
        PaymentIntentSearchResult matches = new PaymentIntentSearchResult();
        matches.setData(List.of(original));
        paymentIntentStatic.when(() -> PaymentIntent.search(any(PaymentIntentSearchParams.class),
                any(RequestOptions.class))).thenReturn(matches);
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("network down"));

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
    }

    @Test
    void refundIdempotencyConflict_isIndeterminate_andCarriesNoFabricatedRefundId() throws Exception {
        PaymentIntent original = intent("pi_orig", "succeeded");
        PaymentIntentSearchResult matches = new PaymentIntentSearchResult();
        matches.setData(List.of(original));
        paymentIntentStatic.when(() -> PaymentIntent.search(any(PaymentIntentSearchParams.class),
                any(RequestOptions.class))).thenReturn(matches);
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new IdempotencyException("idempotency conflict", "req_refund_1", "idempotency_error", 400));

        PaymentProvider.ProviderResult result = provider.refund(
                PAYMENT_ID, AMOUNT, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isFalse();
        assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.INDETERMINATE);
        assertThat(result.providerEventId()).isEqualTo("idempotency-conflict");
    }

    @Test
    void blankSecretKey_failsFastAtBeanCreation() {

        com.shop.paymentservice.config.PaymentStripeProperties blank =
                com.shop.paymentservice.config.PaymentStripeProperties.of("", "whsec", "2024-06-20", "", false);
        assertThatThrownBy(() -> new StripeProvider(blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key");
    }

    @Test
    void nameIsStripe() {
        assertThat(provider.name()).isEqualTo("stripe");
    }
}
