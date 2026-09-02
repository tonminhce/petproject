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
 *   <li>{@code api-version} — the Stripe wire version pinned onto every SDK
 *       call via {@code RequestOptions.setStripeVersionOverride} (stripe-java
 *       24.x has no mutable {@code Stripe.apiVersion} global — the constant is
 *       final — so the pin is applied per-request by the adapter).</li>
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

    /**
     * The Stripe-Version to pin on SDK calls — the configured version only
     * when the provider is actually activating (secret key non-blank);
     * {@code null} under the mock default so the SDK global default stays
     * untouched and no code path silently assumes Stripe semantics.
     */
    public String stripeVersionOverride() {
        return secretKey.isBlank() ? null : apiVersion;
    }
}
