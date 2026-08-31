package com.shop.paymentservice.provider;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentProvider {

    String name();

    ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey);

    ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey);

    record ProviderResult(String providerEventId, boolean accepted) {
    }
}
