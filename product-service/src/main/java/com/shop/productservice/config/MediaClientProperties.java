package com.shop.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code shop.media.*} — the media-service existence-check endpoint used
 * by the Option C write-time validation (media epic spec D5) and the Keycloak
 * service-token endpoint (client_credentials flow).
 *
 * <p>Local to product-service deliberately (rating/search precedent): keycloak
 * settings are NOT {@code shop.keycloak} (auth-owned) and the outbound service
 * config is NOT a shared {@code shop.services} block — media is the one
 * downstream of the write-time gate. Mirrors rating-service's
 * {@code RatingClientProperties} record style.</p>
 *
 * @param baseUrl  media-service base URL (compose-era default
 *                 {@code http://media-service:8083})
 * @param timeoutMs HTTP connect/read timeout in milliseconds (default 3000)
 * @param keycloak client_credentials settings (token-url, client-id, client-secret)
 */
@ConfigurationProperties(prefix = "shop.media")
public record MediaClientProperties(
        String baseUrl,
        long timeoutMs,
        Keycloak keycloak
) {

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
