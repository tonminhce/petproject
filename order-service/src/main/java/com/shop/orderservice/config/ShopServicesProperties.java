package com.shop.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
        Service product,
        Service inventory,
        Service tax,
        Service promotion,
        Service payment,
        Keycloak keycloak
) {

    public record Service(String url, int timeoutMs, Boolean enabled) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
