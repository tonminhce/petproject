package com.shop.paymentservice.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentProviderConfig.class, MockProvider.class, StripeProvider.class);

    @Test
    void mockIsActiveByDefaultAndResolvesAsPrimary() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasBean("mockProvider");
            assertThat(ctx).doesNotHaveBean("stripeProvider");
            assertThat(ctx.getBean(PaymentProvider.class)).isSameAs(ctx.getBean("mockProvider"));
            assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("mock");
        });
    }

    @Test
    void stripeSelectedWhenConfiguredWithSecretKey() {
        contextRunner.withPropertyValues(
                        "shop.payment.provider=stripe",
                        "shop.payment.stripe.secret-key=sk_test_123")
                .run(ctx -> {
                    assertThat(ctx).hasBean("stripeProvider");
                    assertThat(ctx).doesNotHaveBean("mockProvider");
                    assertThat(ctx.getBean(PaymentProvider.class)).isSameAs(ctx.getBean("stripeProvider"));
                    assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("stripe");
                });
    }

    @Test
    void stripeFailsFastOnBlankSecretKey() {
        contextRunner.withPropertyValues(
                        "shop.payment.provider=stripe",
                        "shop.payment.stripe.secret-key=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("STRIPE_SECRET_KEY");
                });
    }

    @Test
    void failsWhenNotExactlyOneProviderActive() {
        new ApplicationContextRunner()
                .withUserConfiguration(PaymentProviderConfig.class, MockProvider.class, ExtraProviderConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("exactly one");
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
