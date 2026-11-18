package com.shop.paymentservice.provider;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderRoutingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentProviderConfig.class, MockProvider.class, CodProvider.class);

    @Test
    void unknownProviderName_isRejectedAndNeverSubstitutedByTheDefaultProvider() {
        PaymentProviderFactory factory = new PaymentProviderFactory(List.of(new MockProvider(), new CodProvider()), "mock");

        assertThatThrownBy(() -> factory.getProvider("paypal"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
        assertThat(factory.getProvider("mock").name()).isEqualTo("mock");
        assertThat(factory.getAllProviders()).containsOnlyKeys("MOCK", "COD");
    }

    @Test
    void blankProviderName_isRejectedInsteadOfDefaulting() {
        PaymentProviderFactory factory = new PaymentProviderFactory(List.of(new MockProvider()), "mock");

        assertThatThrownBy(() -> factory.getProvider(" "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
    }

    @Test
    void unregisteredDefaultProvider_failsFastInsteadOfPickingAnArbitraryProvider() {
        PaymentProviderFactory factory = new PaymentProviderFactory(List.of(new MockProvider()), "stripe");

        assertThatThrownBy(factory::getDefaultProvider)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
    }

    @Test
    void primaryBeanFailsFastWhenTheConfiguredProviderIsNotRegistered() {
        contextRunner.withPropertyValues(
                        "shop.payment.provider=stripe",
                        "shop.payment.stripe.secret-key=sk_test_123")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("not registered");
                });
    }

    @Test
    void mockProviderBeanIsAbsentWhenAnotherProviderIsConfigured() {
        contextRunner.withPropertyValues("shop.payment.provider=cod").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean("mockProvider");
            assertThat(ctx.getBean(PaymentProvider.class).name()).isEqualTo("COD");
            PaymentProviderFactory factory = ctx.getBean(PaymentProviderFactory.class);
            assertThat(factory.getAllProviders()).doesNotContainKey("MOCK");
            assertThatThrownBy(() -> factory.getProvider("mock"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.PAYMENT_PROVIDER_REJECTED.getCode()));
        });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
