package com.shop.paymentservice.provider;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and factory for payment providers supporting multi-provider dispatch.
 */
public class PaymentProviderFactory {

    private final Map<String, PaymentProvider> providers = new ConcurrentHashMap<>();
    private final String defaultProviderName;

    public PaymentProviderFactory(List<PaymentProvider> providerList, String defaultProviderName) {
        if (providerList != null) {
            for (PaymentProvider p : providerList) {
                String name = (p != null && p.name() != null) ? p.name().toUpperCase(Locale.ROOT) : "MOCK";
                if (p != null) {
                    this.providers.put(name, p);
                }
            }
        }
        this.defaultProviderName = (defaultProviderName != null && !defaultProviderName.isBlank())
                ? defaultProviderName.toUpperCase(Locale.ROOT)
                : "MOCK";
    }

    public PaymentProvider getProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return getDefaultProvider();
        }
        PaymentProvider p = providers.get(providerName.trim().toUpperCase(Locale.ROOT));
        if (p == null) {
            return getDefaultProvider();
        }
        return p;
    }

    public PaymentProvider getDefaultProvider() {
        PaymentProvider defaultProvider = providers.get(defaultProviderName);
        if (defaultProvider != null) {
            return defaultProvider;
        }
        if (!providers.isEmpty()) {
            return providers.values().iterator().next();
        }
        throw BusinessException.of(ErrorCode.PAYMENT_PROVIDER_REJECTED,
                "No payment providers configured in the system");
    }

    public Map<String, PaymentProvider> getAllProviders() {
        return Collections.unmodifiableMap(providers);
    }
}
