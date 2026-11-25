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
        String sandboxPaymentMethod,
        String returnUrl,
        boolean allowRedirects) {

    public static PaymentStripeProperties of(
            String secretKey,
            String webhookSecret,
            String apiVersion,
            String apiBase,
            boolean connect) {
        return new PaymentStripeProperties(secretKey, webhookSecret, apiVersion, apiBase, connect, true, false, "pm_card_visa", null, true);
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
        if (returnUrl == null || returnUrl.isBlank()) {
            returnUrl = DEFAULT_RETURN_URL;
        } else {
            returnUrl = returnUrl.trim();
        }
    }

    /**
     * Stripe refuses to confirm a PaymentIntent that was created with automatic payment methods
     * unless the intent carries a return url, so every intent carries one.
     */
    public static final String DEFAULT_RETURN_URL = "https://shop.example.com/checkout/complete";

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
