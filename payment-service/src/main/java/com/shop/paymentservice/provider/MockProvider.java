package com.shop.paymentservice.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
// A11: no `matchIfMissing` — a provider must be selected explicitly (A12).
// C5 (spec §3): the yml default is now mock (env SHOP_PAYMENT_PROVIDER); dev
// compose injects SHOP_PAYMENT_PROVIDER=mock explicitly and prod must opt into
// stripe. Mock "capture" never charges — real money requires provider=stripe.
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "mock")
public class MockProvider implements PaymentProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return new ProviderResult("mock-" + UUID.randomUUID(), true);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        return new ProviderResult("mock-" + UUID.randomUUID(), true);
    }
}
