package com.shop.searchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
        Service product,
        Keycloak keycloak
) {

    public record Service(String url, long timeoutMs) {}

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
