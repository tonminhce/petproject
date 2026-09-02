package com.shop.mediaservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code shop.product.*} — the product-service internal reference-count
 * endpoint consumed by the purge gate (H-4) and the Keycloak service-token
 * endpoint (client_credentials flow).
 *
 * <p>Local to media-service deliberately (rating/search/product precedent):
 * keycloak settings are NOT {@code shop.keycloak} (auth-owned) and the
 * outbound service config is NOT a shared {@code shop.services} block —
 * product is media's one downstream dependency.</p>
 *
 * @param baseUrl  product-service base URL (compose-era default
 *                 {@code http://product-service:8086})
 * @param timeoutMs HTTP connect/read timeout in milliseconds (default 3000)
 * @param keycloak client_credentials settings (token-url, client-id, client-secret)
 */
@ConfigurationProperties(prefix = "shop.product")
public record ProductClientProperties(
        String baseUrl,
        long timeoutMs,
        Keycloak keycloak
) {

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
