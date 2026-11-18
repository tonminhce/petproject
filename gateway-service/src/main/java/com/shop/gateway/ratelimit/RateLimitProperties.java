package com.shop.gateway.ratelimit;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") @Positive int replenishRate,
        @DefaultValue("200") @Positive long burstCapacity,
        @DefaultValue("1") @Positive int requestedTokens,
        @DefaultValue("FAIL_CLOSED") RedisOutagePolicy outagePolicy
) {

    public RateLimitProperties {
        if (burstCapacity < requestedTokens) {
            throw new IllegalArgumentException("burstCapacity must be >= requestedTokens");
        }
        outagePolicy = outagePolicy == null ? RedisOutagePolicy.FAIL_CLOSED : outagePolicy;
    }
}
