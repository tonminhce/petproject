package com.shop.ratingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind {@code shop.rating.*} client-side settings — the order-service endpoint
 * for purchase verification and the Keycloak service-token endpoint
 * (client_credentials flow).
 *
 * <p>Local to rating-service deliberately: keycloak settings are NOT
 * {@code shop.keycloak} (auth-owned) and the outbound service config is NOT a
 * shared {@code shop.services} block — rating has exactly one downstream
 * (order-service). Mirrors order-service's {@code ShopServicesProperties}
 * record style.</p>
 *
 * @param orderService order-service base URL + timeout for verify-purchase calls
 * @param keycloak     client_credentials settings (token-url, client-id, client-secret)
 */
@ConfigurationProperties(prefix = "shop.rating")
public record RatingClientProperties(
        OrderService orderService,
        Keycloak keycloak
) {

    /**
     * @param url       base URL of order-service (e.g. {@code http://localhost:8084})
     * @param timeoutMs HTTP connect/read timeout in milliseconds (default 3000)
     */
    public record OrderService(String url, long timeoutMs) {}

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
