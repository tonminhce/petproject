package com.shop.orderservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bind {@code shop.services.*} — endpoints, credentials, and tokens for downstream
 * inter-service calls (product / inventory / tax / promotion) plus the Keycloak
 * service-token endpoint (client_credentials flow).
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
@Validated
@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
        Service product,
        Service inventory,
        Service tax,
        Service promotion,
        Keycloak keycloak
) {

    public record Service(@NotBlank String url) {}

    public record Keycloak(
            @NotBlank String tokenUrl,
            @NotBlank String clientId,
            @NotBlank String clientSecret
    ) {}
}