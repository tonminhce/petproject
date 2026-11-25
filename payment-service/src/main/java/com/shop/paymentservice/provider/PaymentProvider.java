package com.shop.paymentservice.provider;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentProvider {

    String name();

    ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey);

    ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey);

    default ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey,
            String providerIntentId) {
        return refund(paymentId, amount, currency, idempotencyKey);
    }

    enum Outcome {
        CONFIRMED,
        REQUIRES_CONFIRMATION,
        REJECTED,
        INDETERMINATE
    }

    record ProviderResult(String providerEventId, Outcome outcome, String clientSecret, String returnUrl) {

        public ProviderResult {
            if (outcome == null) {
                outcome = Outcome.INDETERMINATE;
            }
        }

        public ProviderResult(String providerEventId, boolean accepted) {
            this(providerEventId, accepted ? Outcome.CONFIRMED : Outcome.REJECTED, null, null);
        }

        public ProviderResult(String providerEventId, Outcome outcome) {
            this(providerEventId, outcome, null, null);
        }

        public ProviderResult(String providerEventId, Outcome outcome, String clientSecret) {
            this(providerEventId, outcome, clientSecret, null);
        }

        public boolean accepted() {
            return outcome == Outcome.CONFIRMED;
        }

        public static ProviderResult confirmed(String providerEventId) {
            return new ProviderResult(providerEventId, Outcome.CONFIRMED, null);
        }

        public static ProviderResult requiresConfirmation(String providerEventId, String clientSecret) {
            return new ProviderResult(providerEventId, Outcome.REQUIRES_CONFIRMATION, clientSecret, null);
        }

        public static ProviderResult requiresConfirmation(String providerEventId, String clientSecret,
                String returnUrl) {
            return new ProviderResult(providerEventId, Outcome.REQUIRES_CONFIRMATION, clientSecret, returnUrl);
        }

        public static ProviderResult rejected(String providerEventId) {
            return new ProviderResult(providerEventId, Outcome.REJECTED, null);
        }

        public static ProviderResult indeterminate(String providerEventId) {
            return new ProviderResult(providerEventId, Outcome.INDETERMINATE, null);
        }
    }
}
