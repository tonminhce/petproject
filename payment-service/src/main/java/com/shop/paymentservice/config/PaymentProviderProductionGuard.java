package com.shop.paymentservice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
@ConditionalOnProperty(name = "shop.payment.production-mode", havingValue = "true")
public class PaymentProviderProductionGuard {

    private static final String MOCK_PROVIDER = "mock";
    private static final String COD_PROVIDER = "cod";

    private final String provider;
    private final PaymentStripeProperties stripeProperties;
    private final PaymentMoMoProperties momoProperties;
    private final PaymentVNPayProperties vnpayProperties;

    public PaymentProviderProductionGuard(
            @Value("${shop.payment.provider:}") String provider,
            PaymentStripeProperties stripeProperties,
            PaymentMoMoProperties momoProperties,
            PaymentVNPayProperties vnpayProperties) {
        this.provider = provider;
        this.stripeProperties = stripeProperties;
        this.momoProperties = momoProperties;
        this.vnpayProperties = vnpayProperties;
    }

    @PostConstruct
    void verifySelectedProviderIsIntegrated() {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("shop.payment.provider must name an integrated provider in production");
        }
        if (MOCK_PROVIDER.equals(normalized)) {
            throw new IllegalStateException(
                    "The mock payment provider simulates captures and is not selectable in production");
        }
        if (COD_PROVIDER.equals(normalized)) {
            return;
        }
        if ("stripe".equals(normalized)) {
            require(stripeProperties.isConfigured(), "stripe", "SHOP_PAYMENT_STRIPE_SECRET_KEY");
            return;
        }
        if ("momo".equals(normalized)) {
            require(momoProperties.isConfigured(), "momo", "SHOP_PAYMENT_MOMO_PARTNER_CODE, SHOP_PAYMENT_MOMO_ACCESS_KEY, "
                    + "SHOP_PAYMENT_MOMO_SECRET_KEY, SHOP_PAYMENT_MOMO_BASE_URL");
            return;
        }
        if ("vnpay".equals(normalized)) {
            require(vnpayProperties.isConfigured(), "vnpay", "SHOP_PAYMENT_VNPAY_TMN_CODE, SHOP_PAYMENT_VNPAY_HASH_SECRET, "
                    + "SHOP_PAYMENT_VNPAY_PAYMENT_URL, SHOP_PAYMENT_VNPAY_API_URL");
        }
    }

    private void require(boolean configured, String providerName, String requiredVariables) {
        if (!configured) {
            throw new IllegalStateException("Payment provider " + providerName
                    + " is not integrated in this deployment: set " + requiredVariables);
        }
    }
}
