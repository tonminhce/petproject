package com.shop.paymentservice.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class CodProvider implements PaymentProvider {

    @Override
    public String name() {
        return "COD";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return ProviderResult.confirmed("cod_capture_" + paymentId);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return ProviderResult.confirmed("cod_refund_" + paymentId);
    }
}
