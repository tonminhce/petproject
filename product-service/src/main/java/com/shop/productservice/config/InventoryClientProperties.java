package com.shop.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.inventory")
public record InventoryClientProperties(
        String baseUrl,
        long timeoutMs
) {
}
