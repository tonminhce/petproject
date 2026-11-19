package com.shop.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.media")
public record MediaClientProperties(
        String baseUrl,
        long timeoutMs,
        Keycloak keycloak
) {

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
