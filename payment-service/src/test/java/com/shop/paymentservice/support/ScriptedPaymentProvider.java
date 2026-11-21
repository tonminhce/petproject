package com.shop.paymentservice.support;

import com.shop.paymentservice.provider.PaymentProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ScriptedPaymentProvider implements PaymentProvider {

    private final AtomicReference<ProviderResult> captureResult =
            new AtomicReference<>(ProviderResult.confirmed("scripted-capture"));
    private final AtomicReference<ProviderResult> refundResult =
            new AtomicReference<>(ProviderResult.confirmed("scripted-refund"));
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();
    private final List<String> refundKeys = new CopyOnWriteArrayList<>();
    private final AtomicLong refundDelayMillis = new AtomicLong();

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return captureResult.get();
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
        return refund(paymentId, amount, currency, idempotencyKey, null);
    }

    @Override
    public ProviderResult refund(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey,
            String providerIntentId) {
        refundCalls.add(paymentId + ":" + currency + ":" + providerIntentId);
        refundKeys.add(idempotencyKey);
        delay();
        return refundResult.get();
    }

    private void delay() {
        long millis = refundDelayMillis.get();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void scriptCapture(ProviderResult result) {
        captureResult.set(result);
    }

    public void scriptRefund(ProviderResult result) {
        refundResult.set(result);
    }

    public void scriptRefundDelay(long millis) {
        refundDelayMillis.set(millis);
    }

    public List<String> refundCalls() {
        return List.copyOf(refundCalls);
    }

    public List<String> refundKeys() {
        return List.copyOf(refundKeys);
    }

    public void reset() {
        captureResult.set(ProviderResult.confirmed("scripted-capture"));
        refundResult.set(ProviderResult.confirmed("scripted-refund"));
        refundDelayMillis.set(0);
        refundCalls.clear();
        refundKeys.clear();
    }
}
