package com.shop.ratingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.rating")
public record RatingClientProperties(
        OrderService orderService,
        Keycloak keycloak
) {

    public record OrderService(String url, long timeoutMs) {}

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
