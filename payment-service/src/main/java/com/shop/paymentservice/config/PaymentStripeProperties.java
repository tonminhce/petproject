package com.shop.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.payment.stripe")
public record PaymentStripeProperties(
        String secretKey,
        String webhookSecret,
        String apiVersion,
        String apiBase,
        boolean connect) {

    public PaymentStripeProperties {
        if (secretKey == null) {
            secretKey = "";
        } else {
            secretKey = secretKey.trim();
        }
        if (webhookSecret == null) {
            webhookSecret = "";
        } else {
            webhookSecret = webhookSecret.trim();
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = "2024-06-20";
        }
        if (apiBase == null) {
            apiBase = "";
        } else {
            apiBase = apiBase.trim();
        }
    }

    public boolean isConfigured() {
        return !secretKey.isBlank();
    }

    public boolean isVersionDriftedFromSdk() {
        return isConfigured() && !apiVersion.equals(com.stripe.Stripe.API_VERSION);
    }
}
