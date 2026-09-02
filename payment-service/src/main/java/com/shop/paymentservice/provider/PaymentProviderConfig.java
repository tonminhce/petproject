package com.shop.paymentservice.provider;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.Assert;

import java.util.List;

@Configuration
public class PaymentProviderConfig {

    private final String provider;
    private final String stripeSecretKey;

    public PaymentProviderConfig(
            @Value("${shop.payment.provider:}") String provider,
            @Value("${shop.payment.stripe.secret-key:}") String stripeSecretKey) {
        this.provider = provider;
        this.stripeSecretKey = stripeSecretKey;
    }

    /**
     * C5 Task 4 — fail-fast (D10): provider=stripe with no secret key must
     * never reach a half-wired context. Belt alongside StripeProvider's own
     * constructor guard — the message names the env var ops has to set.
     */
    @PostConstruct
    void verifyStripeCredentialsWhenSelected() {
        if ("stripe".equals(provider) && (stripeSecretKey == null || stripeSecretKey.isBlank())) {
            throw new IllegalStateException(
                    "StripeProvider requires shop.payment.stripe.secret-key env (SHOP_PAYMENT_STRIPE_SECRET_KEY)");
        }
    }

    @Bean
    @Primary
    public PaymentProvider primary(List<PaymentProvider> all) {
        Assert.state(all.size() == 1,
                () -> "Expected exactly one active PaymentProvider but found " + all.size());
        return all.get(0);
    }
}
