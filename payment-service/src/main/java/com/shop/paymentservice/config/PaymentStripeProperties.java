package com.shop.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.payment.stripe")
public record PaymentStripeProperties(
        String secretKey,
        String webhookSecret,
        String apiVersion,
        boolean connect) {

    public PaymentStripeProperties {
        if (secretKey == null) {
            secretKey = "";
        }
        if (webhookSecret == null) {
            webhookSecret = "";
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = "2024-06-20";
        }
    }

    /** True when the stripe provider is actually activating (key present). */
    public boolean isConfigured() {
        return !secretKey.isBlank();
    }

    public boolean isVersionDriftedFromSdk() {
        return isConfigured() && !apiVersion.equals(com.stripe.Stripe.API_VERSION);
    }
}
