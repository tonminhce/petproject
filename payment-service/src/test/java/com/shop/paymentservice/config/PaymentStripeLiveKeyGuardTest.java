package com.shop.paymentservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStripeLiveKeyGuardTest {

    private static PaymentStripeProperties properties(String secretKey) {
        return new PaymentStripeProperties(secretKey, null, null, null, false, true, false, "pm_card_visa", null, true);
    }

    @Test
    @DisplayName("a live mode secret key fails the startup without an explicit opt-in")
    void aLiveModeSecretKeyFailsTheStartup() {
        var guard = new PaymentStripeLiveKeyGuard(properties("sk_live_51Hxxxxxxxxxxxxxxxxxxxx"), false);

        assertThatThrownBy(guard::refuseLiveKeyOutsideAnExplicitlyAllowedDeployment)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shop.payment.allow-live-stripe-keys");
    }

    @Test
    @DisplayName("a restricted live mode secret key fails the startup without an explicit opt-in")
    void aRestrictedLiveModeSecretKeyFailsTheStartup() {
        var guard = new PaymentStripeLiveKeyGuard(properties("rk_live_51Hxxxxxxxxxxxxxxxxxxxx"), false);

        assertThatThrownBy(guard::refuseLiveKeyOutsideAnExplicitlyAllowedDeployment)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a live mode secret key starts in a deployment that opts in explicitly")
    void aLiveModeSecretKeyStartsWithTheExplicitOptIn() {
        var guard = new PaymentStripeLiveKeyGuard(properties("sk_live_51Hxxxxxxxxxxxxxxxxxxxx"), true);

        assertThatCode(guard::refuseLiveKeyOutsideAnExplicitlyAllowedDeployment).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a test mode secret key starts in every deployment")
    void aTestModeSecretKeyStarts() {
        var guard = new PaymentStripeLiveKeyGuard(properties("sk_test_51Hxxxxxxxxxxxxxxxxxxxx"), false);

        assertThatCode(guard::refuseLiveKeyOutsideAnExplicitlyAllowedDeployment).doesNotThrowAnyException();
    }
}
