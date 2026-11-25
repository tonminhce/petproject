package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class StripeProviderReturnUrlTest {

    private static final String RETURN_URL = "https://shop.test/checkout/complete";

    private PaymentIntentCreateParams capturedParams(boolean autoConfirmSandbox, boolean allowRedirects) {
        PaymentStripeProperties properties = new PaymentStripeProperties(
                "sk_test_return_url", "whsec_test", "2024-06-20", "",
                false, true, autoConfirmSandbox, "pm_card_visa", RETURN_URL, allowRedirects);
        StripeProvider provider = new StripeProvider(properties);

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        try (MockedStatic<PaymentIntent> statics = mockStatic(PaymentIntent.class)) {
            PaymentIntent intent = new PaymentIntent();
            intent.setId("pi_return_url");
            intent.setStatus("requires_payment_method");
            intent.setAmount(1000L);
            intent.setCurrency("usd");
            statics.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any()))
                    .thenReturn(intent);

            provider.capture(UUID.randomUUID(), new BigDecimal("10.00"), "USD", "idem-key");
            statics.verify(() -> PaymentIntent.create(captor.capture(), any()));
        }
        return captor.getValue();
    }

    @Test
    @DisplayName("the intent the provider confirms itself carries the return url")
    void theConfirmedIntentCarriesTheReturnUrl() {
        PaymentIntentCreateParams params = capturedParams(true, true);

        assertThat(params.getReturnUrl()).isEqualTo(RETURN_URL);
        assertThat(params.getConfirm()).isEqualTo(true);
    }

    @Test
    @DisplayName("an intent that the client confirms carries no return url because stripe rejects one")
    void theClientConfirmedIntentCarriesNoReturnUrl() {
        PaymentIntentCreateParams params = capturedParams(false, true);

        assertThat(params.getReturnUrl()).isNull();
        assertThat(params.getConfirm()).isEqualTo(false);
        assertThat(params.getAutomaticPaymentMethods().getEnabled()).isTrue();
        assertThat(params.getAutomaticPaymentMethods().getAllowRedirects())
                .isEqualTo(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.ALWAYS);
    }

    @Test
    @DisplayName("a deployment that refuses redirect methods turns the allow redirects switch off")
    void theAllowRedirectsSwitchIsOff() {
        PaymentIntentCreateParams params = capturedParams(false, false);

        assertThat(params.getAutomaticPaymentMethods().getAllowRedirects())
                .isEqualTo(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER);
    }

    @Test
    @DisplayName("the provider hands the return url to the caller that confirms the intent")
    void theProviderHandsTheReturnUrlToTheCaller() {
        PaymentStripeProperties properties = new PaymentStripeProperties(
                "sk_test_return_url", "whsec_test", "2024-06-20", "",
                false, true, false, "pm_card_visa", RETURN_URL, true);
        StripeProvider provider = new StripeProvider(properties);

        try (MockedStatic<PaymentIntent> statics = mockStatic(PaymentIntent.class)) {
            PaymentIntent intent = new PaymentIntent();
            intent.setId("pi_return_url");
            intent.setStatus("requires_payment_method");
            intent.setAmount(1000L);
            intent.setCurrency("usd");
            intent.setClientSecret("pi_return_url_secret");
            statics.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any()))
                    .thenReturn(intent);

            PaymentProvider.ProviderResult result = provider.capture(
                    UUID.randomUUID(), new BigDecimal("10.00"), "USD", "idem-key");

            assertThat(result.outcome()).isEqualTo(PaymentProvider.Outcome.REQUIRES_CONFIRMATION);
            assertThat(result.returnUrl()).isEqualTo(RETURN_URL);
            assertThat(result.clientSecret()).isEqualTo("pi_return_url_secret");
        }
    }
}