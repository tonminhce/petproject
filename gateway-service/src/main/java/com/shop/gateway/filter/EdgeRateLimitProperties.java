package com.shop.gateway.filter;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.edge-rate-limit")
public record EdgeRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") @Positive int backofficeRequestsPerMinute,
        @DefaultValue("60") @Positive int searchRequestsPerMinute
) {
    @ConstructorBinding
    public EdgeRateLimitProperties {
    }

}
