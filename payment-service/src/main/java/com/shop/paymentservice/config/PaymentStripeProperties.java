package com.shop.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * C5 Task 1 — Stripe provider credentials + behaviour (spec §3). Env-driven
 * activation: {@code SHOP_PAYMENT_PROVIDER=stripe} selects the StripeProvider
 * bean; this record carries the keys it needs.
 *
 * <ul>
 *   <li>{@code secret-key} — {@code SHOP_PAYMENT_STRIPE_SECRET_KEY} (sk_test_*
 *       / sk_live_*); blank under the mock default, so binding must tolerate
 *       empty values.</li>
 *   <li>{@code webhook-secret} — {@code SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET};
 *       separate from the generic HMAC {@code shop.payment.webhook.secret}
 *       because Stripe signs with its own t=/v1= scheme.</li>
 *   <li>{@code api-version} — declared pin (spec §3). Per D1 the stripe-java
 *       SDK itself pins the Stripe-Version header on the wire
 *       ({@code Stripe.API_VERSION} is a final constant in 24.x — there is no
 *       mutable global); a configured value that drifts from the SDK constant
 *       is surfaced as a startup WARN, never silently accepted as a pin.</li>
 *   <li>{@code connect} — V1 is single-account; Stripe Connect deferred.</li>
 * </ul>
 */
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

    /**
     * C5 Task 1/2 — True when a configured pin (spec §3) drifts from the SDK's
     * own {@code Stripe.API_VERSION}. Only meaningful when
     * {@link #isConfigured()} — under the mock default no Stripe call exists.
     */
    public boolean isVersionDriftedFromSdk() {
        return isConfigured() && !apiVersion.equals(com.stripe.Stripe.API_VERSION);
    }
}
