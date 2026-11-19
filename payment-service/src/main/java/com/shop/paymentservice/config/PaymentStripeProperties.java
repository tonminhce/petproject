package com.shop.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.payment.stripe")
public record PaymentStripeProperties(
        String secretKey,
        String webhookSecret,
        String apiVersion,
        String apiBase,
        boolean connect,
        boolean sandbox,
        boolean autoConfirmSandbox,
        String sandboxPaymentMethod) {

    public static PaymentStripeProperties of(
            String secretKey,
            String webhookSecret,
            String apiVersion,
            String apiBase,
            boolean connect) {
        return new PaymentStripeProperties(secretKey, webhookSecret, apiVersion, apiBase, connect, true, false, "pm_card_visa");
    }

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
        if (sandboxPaymentMethod == null || sandboxPaymentMethod.isBlank()) {
            sandboxPaymentMethod = "pm_card_visa";
        }
    }

    public boolean isConfigured() {
        return !secretKey.isBlank();
    }

    public boolean isSandbox() {
        return sandbox || (!secretKey.isBlank() && secretKey.startsWith("sk_test_"));
    }

    public boolean isVersionDriftedFromSdk() {
        return isConfigured() && !apiVersion.equals(com.stripe.Stripe.API_VERSION);
    }
}
