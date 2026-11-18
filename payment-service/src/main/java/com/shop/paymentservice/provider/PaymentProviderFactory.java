package com.shop.paymentservice.provider;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaymentProviderFactory {

    private final Map<String, PaymentProvider> providers = new ConcurrentHashMap<>();
    private final String defaultProviderName;

    public PaymentProviderFactory(List<PaymentProvider> providerList, String defaultProviderName) {
        if (providerList != null) {
            for (PaymentProvider p : providerList) {
                if (p == null || p.name() == null || p.name().isBlank()) {
                    continue;
                }
                this.providers.put(p.name().trim().toUpperCase(Locale.ROOT), p);
            }
        }
        this.defaultProviderName = defaultProviderName == null
                ? ""
                : defaultProviderName.trim().toUpperCase(Locale.ROOT);
    }

    public PaymentProvider getProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED,
                    "A payment provider name is required");
        }
        PaymentProvider provider = providers.get(providerName.trim().toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED,
                    "Payment provider is not registered: " + providerName);
        }
        return provider;
    }

    public PaymentProvider getDefaultProvider() {
        PaymentProvider defaultProvider = providers.get(defaultProviderName);
        if (defaultProvider == null) {
            throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED,
                    "Default payment provider is not registered: " + defaultProviderName);
        }
        return defaultProvider;
    }

    public Map<String, PaymentProvider> getAllProviders() {
        return Collections.unmodifiableMap(providers);
    }
}
