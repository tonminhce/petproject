package com.shop.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.payment.vnpay")
public record PaymentVNPayProperties(
        String paymentUrl,
        String apiUrl,
        String tmnCode,
        String hashSecret,
        String returnUrl,
        String notifyUrl,
        String bankCode,
        String orderInfo,
        String ipAddress,
        String userName,
        String refundType,
        String transactionDate,
        long timeoutMs) {

    public PaymentVNPayProperties {
        paymentUrl = trimToEmpty(paymentUrl);
        apiUrl = trimToEmpty(apiUrl);
        tmnCode = trimToEmpty(tmnCode);
        hashSecret = trimToEmpty(hashSecret);
        returnUrl = trimToEmpty(returnUrl);
        notifyUrl = trimToEmpty(notifyUrl);
        bankCode = trimToEmpty(bankCode);
        orderInfo = orderInfo == null || orderInfo.isBlank() ? "Payment" : orderInfo.trim();
        ipAddress = ipAddress == null || ipAddress.isBlank() ? "127.0.0.1" : ipAddress.trim();
        userName = trimToEmpty(userName);
        refundType = refundType == null || refundType.isBlank() ? "partial" : refundType.trim().toLowerCase();
        transactionDate = trimToEmpty(transactionDate);
        timeoutMs = timeoutMs <= 0 ? 30000L : timeoutMs;
    }

    public boolean isConfigured() {
        return !paymentUrl.isBlank() && !apiUrl.isBlank() && !tmnCode.isBlank() && !hashSecret.isBlank()
                && !returnUrl.isBlank() && !notifyUrl.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
