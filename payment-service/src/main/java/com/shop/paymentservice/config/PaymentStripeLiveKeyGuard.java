package com.shop.paymentservice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
public class PaymentStripeLiveKeyGuard {

    private static final String LIVE_KEY_PREFIX = "sk_live_";

    private final PaymentStripeProperties stripeProperties;
    private final boolean allowLiveKeys;

    public PaymentStripeLiveKeyGuard(
            PaymentStripeProperties stripeProperties,
            @Value("${shop.payment.allow-live-stripe-keys:false}") boolean allowLiveKeys) {
        this.stripeProperties = stripeProperties;
        this.allowLiveKeys = allowLiveKeys;
    }

    @PostConstruct
    void refuseLiveKeyOutsideAnExplicitlyAllowedDeployment() {
        String secretKey = stripeProperties.secretKey();
        if (secretKey == null) {
            return;
        }
        String normalized = secretKey.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(LIVE_KEY_PREFIX)) {
            return;
        }
        if (allowLiveKeys) {
            return;
        }
        throw new IllegalStateException(
                "A live mode Stripe secret key is configured. Set shop.payment.allow-live-stripe-keys=true "
                        + "only in a deployment that is allowed to move real money.");
    }
}