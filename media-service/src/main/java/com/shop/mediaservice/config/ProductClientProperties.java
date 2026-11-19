package com.shop.mediaservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.product")
public record ProductClientProperties(
        String baseUrl,
        long timeoutMs,
        Keycloak keycloak
) {

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
