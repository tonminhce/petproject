package com.shop.paymentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderProductionGuardTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentProviderProductionGuard.class, PropsConfig.class);

    @Configuration
    @EnableConfigurationProperties({PaymentStripeProperties.class, PaymentMoMoProperties.class, PaymentVNPayProperties.class})
    static class PropsConfig {
    }

    @Test
    void guardIsInactiveOutsideProductionMode() {
        contextRunner.withPropertyValues("shop.payment.provider=mock").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(PaymentProviderProductionGuard.class);
        });
    }

    @Test
    void mockProviderIsRejectedInProductionMode() {
        contextRunner.withPropertyValues(
                        "shop.payment.production-mode=true",
                        "shop.payment.provider=mock")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("not selectable in production");
                });
    }

    @Test
    void anUnconfiguredProviderIsRejectedInProductionMode() {
        contextRunner.withPropertyValues(
                        "shop.payment.production-mode=true",
                        "shop.payment.provider=momo")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("momo");
                });
    }

    @Test
    void anIntegratedProviderIsAcceptedInProductionMode() {
        contextRunner.withPropertyValues(
                        "shop.payment.production-mode=true",
                        "shop.payment.provider=momo",
                        "shop.payment.momo.base-url=https://test-payment.momo.vn",
                        "shop.payment.momo.partner-code=PARTNER",
                        "shop.payment.momo.access-key=ACCESS",
                        "shop.payment.momo.secret-key=SECRET",
                        "shop.payment.momo.return-url=https://shop.test/momo/return",
                        "shop.payment.momo.notify-url=https://shop.test/momo/ipn")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aProviderWithoutTheCallbackUrlsIsRejectedInProductionMode() {
        contextRunner.withPropertyValues(
                        "shop.payment.production-mode=true",
                        "shop.payment.provider=momo",
                        "shop.payment.momo.base-url=https://test-payment.momo.vn",
                        "shop.payment.momo.partner-code=PARTNER",
                        "shop.payment.momo.access-key=ACCESS",
                        "shop.payment.momo.secret-key=SECRET")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("momo");
                });
    }

    @Test
    void codIsAcceptedInProductionMode() {
        contextRunner.withPropertyValues(
                        "shop.payment.production-mode=true",
                        "shop.payment.provider=cod")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void stripeWithoutASecretKeyIsRejectedInProductionMode() {
        contextRunner.withPropertyValues(
                        "shop.payment.production-mode=true",
                        "shop.payment.provider=stripe")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("stripe");
                });
    }
}
