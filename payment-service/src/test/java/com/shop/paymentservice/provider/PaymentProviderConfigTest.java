package com.shop.paymentservice.provider;

import com.shop.paymentservice.config.PaymentStripeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentProviderConfig.class, MockProvider.class, StripeProvider.class,
                    CodProvider.class, PropsConfig.class);

    /**
     * C5 Task 2 — StripeProvider consumes PaymentStripeProperties
     * (secret-key/webhook-secret/api-version/connect); the runner must enable
     * the binding for the stripe-selection cases.
     */
    @EnableConfigurationProperties(PaymentStripeProperties.class)
    static class PropsConfig {
    }

    @Test
    void mockIsActiveWhenExplicitlySelectedAndResolvesAsPrimary() {
        contextRunner.withPropertyValues("shop.payment.provider=mock").run(ctx -> {
            assertThat(ctx).hasBean("mockProvider");
            // Multi-provider: other lightweight providers also load
            assertThat(ctx).hasBean("codProvider");
            assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("mock");
        });
    }

    @Test
    void codResolvesAsPrimaryWhenSelected() {
        contextRunner.withPropertyValues("shop.payment.provider=cod").run(ctx -> {
            assertThat(ctx).hasBean("mockProvider");
            assertThat(ctx).hasBean("codProvider");
            assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("COD");
        });
    }

    @Test
    void stripeSelectedWhenConfiguredWithSecretKey() {
        contextRunner.withPropertyValues(
                        "shop.payment.provider=stripe",
                        "shop.payment.stripe.secret-key=sk_test_123")
                .run(ctx -> {
                    assertThat(ctx).hasBean("stripeProvider");
                    // Multi-provider: mock and cod also load alongside stripe
                    assertThat(ctx).hasBean("mockProvider");
                    assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("stripe");
                });
    }

    @Test
    void stripeFailsFastOnBlankSecretKey() {
        // C5 Task 4 (D10) — config-level belt; StripeProvider's constructor
        // enforces the same rule. Message must name the env var ops has to set.
        contextRunner.withPropertyValues(
                        "shop.payment.provider=stripe",
                        "shop.payment.stripe.secret-key=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("requires shop.payment.stripe.secret-key")
                            .hasMessageContaining("SHOP_PAYMENT_STRIPE_SECRET_KEY");
                });
    }

    @Test
    void multipleProvidersCoexistAndFactoryRoutesCorrectly() {
        new ApplicationContextRunner()
                .withPropertyValues("shop.payment.provider=mock")
                .withUserConfiguration(PaymentProviderConfig.class, MockProvider.class, ExtraProviderConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // Primary resolves to mock per property
                    assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("mock");
                    // Factory contains both providers
                    PaymentProviderFactory factory = ctx.getBean(PaymentProviderFactory.class);
                    assertThat(factory.getProvider("mock").name()).isEqualTo("mock");
                    assertThat(factory.getProvider("extra").name()).isEqualTo("extra");
                });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Configuration
    static class ExtraProviderConfig {

        @Bean
        PaymentProvider extraProvider() {
            return new PaymentProvider() {
                @Override
                public String name() {
                    return "extra";
                }

                @Override
                public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
