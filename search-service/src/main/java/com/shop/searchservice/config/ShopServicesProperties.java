package com.shop.searchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind {@code shop.services.*} — the product-service backoffice endpoint that
 * streams the reindex source (spec D5) and the Keycloak service-token endpoint
 * (client_credentials flow).
 *
 * <p>Order-service {@code ShopServicesProperties} record style; local to
 * search-service deliberately: NOT {@code shop.keycloak} (auth-owned) — see
 * order-service Task 2 P0-7b rationale.</p>
 *
 * @param product  product-service base URL + timeout for reindex paging
 * @param keycloak client_credentials settings (token-url, client-id, client-secret)
 */
@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
        Service product,
        Keycloak keycloak
) {

    /**
     * @param url       base URL of the downstream service (e.g. {@code http://localhost:8086})
     * @param timeoutMs HTTP connect/read timeout in milliseconds (default 3000)
     */
    public record Service(String url, long timeoutMs) {}

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
