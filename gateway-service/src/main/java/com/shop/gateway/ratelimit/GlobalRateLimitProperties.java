package com.shop.gateway.ratelimit;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Capacity guard for all requests that pass through a gateway instance set.
 * The bucket is shared by every gateway node through Redis.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit.global")
public record GlobalRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2000") @Positive int replenishRate,
        @DefaultValue("4000") @Positive long burstCapacity,
        @DefaultValue("1") @Positive int requestedTokens
) {

    public GlobalRateLimitProperties {
        if (burstCapacity < requestedTokens) {
            throw new IllegalArgumentException("burstCapacity must be >= requestedTokens");
        }
    }
}
