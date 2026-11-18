package com.shop.paymentservice.support;

import com.shop.paymentservice.provider.PaymentProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class ScriptedPaymentProvider implements PaymentProvider {

    private final AtomicReference<ProviderResult> captureResult =
            new AtomicReference<>(ProviderResult.confirmed("scripted-capture"));
    private final AtomicReference<ProviderResult> refundResult =
            new AtomicReference<>(ProviderResult.confirmed("scripted-refund"));
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return captureResult.get();
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
        refundCalls.add(paymentId + ":no-intent-id");
        return refundResult.get();
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey, String providerIntentId) {
        refundCalls.add(paymentId + ":" + providerIntentId);
        return refundResult.get();
    }

    public void scriptCapture(ProviderResult result) {
        captureResult.set(result);
    }

    public void scriptRefund(ProviderResult result) {
        refundResult.set(result);
    }

    public List<String> refundCalls() {
        return List.copyOf(refundCalls);
    }

    public void reset() {
        captureResult.set(ProviderResult.confirmed("scripted-capture"));
        refundResult.set(ProviderResult.confirmed("scripted-refund"));
        refundCalls.clear();
    }
}
