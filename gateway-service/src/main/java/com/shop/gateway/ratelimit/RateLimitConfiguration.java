package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    @Bean
    @Primary
    public RedisRateLimiter gatewayRateLimiter(final RateLimitProperties properties) {
        return new RedisRateLimiter(
                properties.replenishRate(),
                properties.burstCapacity(),
                properties.requestedTokens());
    }

    @Bean("globalRateLimiter")
    public RedisRateLimiter globalRateLimiter(final GlobalRateLimitProperties properties) {
        return new RedisRateLimiter(
                properties.replenishRate(),
                properties.burstCapacity(),
                properties.requestedTokens());
    }

    @Bean
    public KeyResolver gatewayRateLimitKeyResolver(final RateLimitProperties properties) {
        return new RateLimitKeyResolver(properties.trustedProxyHops());
    }

    @Bean
    public GlobalRateLimitFilter globalRateLimitFilter(
            @Qualifier("globalRateLimiter") final RateLimiter<?> rateLimiter,
            final RateLimitProperties rateLimitProperties,
            final GlobalRateLimitProperties globalRateLimitProperties,
            final GatewayErrorResponseWriter errorResponseWriter) {
        return new GlobalRateLimitFilter(rateLimiter, rateLimitProperties, globalRateLimitProperties, errorResponseWriter);
    }
}
