package com.shop.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.payment.momo")
public record PaymentMoMoProperties(
        String baseUrl,
        String partnerCode,
        String accessKey,
        String secretKey,
        String returnUrl,
        String notifyUrl,
        String orderInfo,
        long timeoutMs) {

    public PaymentMoMoProperties {
        baseUrl = trimToEmpty(baseUrl);
        partnerCode = trimToEmpty(partnerCode);
        accessKey = trimToEmpty(accessKey);
        secretKey = trimToEmpty(secretKey);
        returnUrl = trimToEmpty(returnUrl);
        notifyUrl = trimToEmpty(notifyUrl);
        orderInfo = orderInfo == null || orderInfo.isBlank() ? "Payment" : orderInfo.trim();
        timeoutMs = timeoutMs <= 0 ? 30000L : timeoutMs;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !partnerCode.isBlank() && !accessKey.isBlank() && !secretKey.isBlank()
                && !returnUrl.isBlank() && !notifyUrl.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
