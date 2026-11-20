package com.shop.gateway.ratelimit;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit.global")
public record GlobalRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2000") @Positive int replenishRate,
        @DefaultValue("4000") @Positive long burstCapacity,
        @DefaultValue("1") @Positive int requestedTokens,
        @DefaultValue("8") @Positive int lanes,
        @DefaultValue("20") @Positive int fairShareClients,
        @DefaultValue("1") @Positive int replicas
) {

    public GlobalRateLimitProperties {
        if (burstCapacity < requestedTokens) {
            throw new IllegalArgumentException("burstCapacity must be >= requestedTokens");
        }
    }
}
