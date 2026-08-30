package com.shop.paymentservice.provider;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "stripe")
public class StripeProvider implements PaymentProvider {

    private final String secretKey;

    public StripeProvider(@Value("${shop.payment.stripe.secret-key:}") String secretKey) {
        this.secretKey = secretKey;
    }

    @PostConstruct
    void verifyCredentials() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe credentials absent — set STRIPE_SECRET_KEY");
        }
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        throw new UnsupportedOperationException("Stripe capture is not implemented yet");
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        throw new UnsupportedOperationException("Stripe refund is not implemented yet");
    }
}
