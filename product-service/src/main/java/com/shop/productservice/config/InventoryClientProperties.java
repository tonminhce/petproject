package com.shop.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code shop.inventory.*} — the inventory-service endpoint that tells
 * the catalogue whether a product still has live stock reservations (F-060).
 *
 * <p>Separate from {@code shop.media.*} on purpose: media is the downstream
 * of the write-time gate, inventory is the downstream of the delete-time
 * gate. The Keycloak service-token settings stay in
 * {@link MediaClientProperties.Keycloak} — the same client identity serves
 * every outbound call of this service.</p>
 *
 * @param baseUrl  inventory-service base URL (compose-era default
 *                 {@code http://inventory-service:8082})
 * @param timeoutMs HTTP connect/read timeout in milliseconds (default 3000)
 */
@ConfigurationProperties(prefix = "shop.inventory")
public record InventoryClientProperties(
        String baseUrl,
        long timeoutMs
) {
}
