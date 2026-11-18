package com.shop.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shop.product.media-sweep")
public record ProductMediaSweepProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 */30 * * * *") String cron,
        @DefaultValue("100") int limit
) {
    public ProductMediaSweepProperties {
        if (limit <= 0) {
            throw new IllegalArgumentException("shop.product.media-sweep.limit must be positive, got: " + limit);
        }
    }
}
