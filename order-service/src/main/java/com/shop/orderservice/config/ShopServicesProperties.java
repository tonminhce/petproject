package com.shop.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
        Service product,
        Service inventory,
        Service tax,
        Service promotion,
        Payment payment,
        Keycloak keycloak
) {

    public record Service(String url, int timeoutMs, Boolean enabled) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    public record Payment(String url, int timeoutMs, Boolean enabled, Integer refundTimeoutMs) {

        public static final int DEFAULT_REFUND_TIMEOUT_MS = 45000;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public int refundTimeout() {
            return refundTimeoutMs == null || refundTimeoutMs <= 0
                    ? DEFAULT_REFUND_TIMEOUT_MS
                    : refundTimeoutMs;
        }
    }

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
