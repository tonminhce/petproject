package com.shop.paymentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5 Task 1 — {@link PaymentStripeProperties} binding contract (spec §3):
 * {@code shop.payment.stripe.{secret-key, webhook-secret, api-version, connect}}
 * binds as a single configuration-properties record. The Stripe API version is
 * pinned onto SDK calls via the {@link PaymentStripeProperties#stripeVersionOverride()}
 * only when a secret key is present (i.e. the stripe provider is actually
 * activating) — a blank key (mock default) yields {@code null} so the SDK
 * global default stays untouched.
 */
class PaymentStripePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentStripePropertiesTest.PropsConfig.class);

    @Test
    void bindsAllFourKeysFromShopPaymentStripeBlock() {
        contextRunner.withPropertyValues(
                        "shop.payment.stripe.secret-key=sk_test_123",
                        "shop.payment.stripe.webhook-secret=whsec_123",
                        "shop.payment.stripe.api-version=2024-06-20",
                        "shop.payment.stripe.connect=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PaymentStripeProperties.class);
                    PaymentStripeProperties props = ctx.getBean(PaymentStripeProperties.class);
                    assertThat(props.secretKey()).isEqualTo("sk_test_123");
                    assertThat(props.webhookSecret()).isEqualTo("whsec_123");
                    assertThat(props.apiVersion()).isEqualTo("2024-06-20");
                    assertThat(props.connect()).isTrue();
                });
    }

    @Test
    void defaultsAreBlankKeysAndPinnedVersionAndNoConnect() {
        contextRunner.run(ctx -> {
            PaymentStripeProperties props = ctx.getBean(PaymentStripeProperties.class);
            assertThat(props.secretKey()).isEmpty();
            assertThat(props.webhookSecret()).isEmpty();
            assertThat(props.apiVersion()).isEqualTo("2024-06-20");
            assertThat(props.connect()).isFalse();
        });
    }

    @Test
    void secretKeyPresencePinsConfiguredApiVersionForSdkCalls() {
        PaymentStripeProperties props = new PaymentStripeProperties(
                "sk_test_123", "whsec_123", "2024-06-20", false);
        assertThat(props.stripeVersionOverride()).isEqualTo("2024-06-20");
    }

    @Test
    void blankSecretKeyYieldsNoVersionOverride() {
        PaymentStripeProperties props = new PaymentStripeProperties(
                "", "whsec_123", "2024-06-20", false);
        assertThat(props.stripeVersionOverride()).isNull();
    }

    @Test
    void blankApiVersionFallsBackToFleetPinnedVersion() {
        PaymentStripeProperties props = new PaymentStripeProperties(
                "sk_test_123", "whsec_123", " ", false);
        assertThat(props.apiVersion()).isEqualTo("2024-06-20");
        assertThat(props.stripeVersionOverride()).isEqualTo("2024-06-20");
    }

    @EnableConfigurationProperties(PaymentStripeProperties.class)
    static class PropsConfig {
    }
}
