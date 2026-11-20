package com.shop.gateway.filter;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.pre-auth-rate-limit")
public record PreAuthRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3000") @Positive int requestsPerMinute
) {

    public PreAuthRateLimitProperties {
    }
}
