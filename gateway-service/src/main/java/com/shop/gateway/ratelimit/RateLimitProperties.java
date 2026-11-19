package com.shop.gateway.ratelimit;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Gateway-wide token-bucket defaults. RedisRateLimiter applies these values
 * independently for each client key and route id.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") @Positive int replenishRate,
        @DefaultValue("200") @Positive long burstCapacity,
        @DefaultValue("1") @Positive int requestedTokens,
        @DefaultValue("0") @PositiveOrZero int trustedProxyHops
) {

    public RateLimitProperties {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("trustedProxyHops must be >= 0");
        }
        if (burstCapacity < requestedTokens) {
            throw new IllegalArgumentException("burstCapacity must be >= requestedTokens");
        }
    }
}
