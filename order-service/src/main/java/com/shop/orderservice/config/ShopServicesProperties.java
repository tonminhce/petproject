package com.shop.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind {@code shop.services.*} — endpoints, timeouts, feature flags, and the
 * Keycloak service-token endpoint (client_credentials flow).
 *
 * <p>Local to order-service deliberately: NOT {@code shop.keycloak} (owned by
 * {@code common-keycloak KeycloakProperties}) — see Task 2 P0-7b rationale.</p>
 *
 * @param product    base URL of product-service
 * @param inventory  base URL of inventory-service
 * @param tax        base URL of tax-service
 * @param promotion  base URL of promotion-service
 * @param keycloak   client_credentials settings (token-url, client-id, client-secret)
 */
@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
        Service product,
        Service inventory,
        Service tax,
        Service promotion,
        Keycloak keycloak
) {

    /**
     * @param url        base URL of the downstream service (e.g. {@code http://localhost:8086})
     * @param timeoutMs  HTTP request/response timeout in milliseconds (default 5000)
     * @param enabled    optional feature flag — when null or true, RestClient bean is registered
     *                   (used by sibling services to skip disabled downstreams like tax/promotion
     *                   before they ship). See {@link #isEnabled()}.
     */
    public record Service(String url, int timeoutMs, Boolean enabled) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
