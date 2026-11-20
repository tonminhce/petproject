package com.shop.gateway.resilience;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.resilience.bulkhead")
public record BulkheadProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200") @Positive int maxConcurrentCalls
) {

    public BulkheadProperties {
    }
}
