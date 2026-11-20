package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.ClientIpResolver;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

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

    @Bean
    public FleetRateLimitBudget fleetRateLimitBudget(final GlobalRateLimitProperties properties) {
        return FleetRateLimitBudget.from(properties);
    }

    @Bean("globalRateLimiter")
    public FleetRateLimiter globalRateLimiter(final ReactiveStringRedisTemplate redis,
                                               final FleetRateLimitBudget budget) {
        return new RedisFleetRateLimiter(redis, budget);
    }

    @Bean
    public KeyResolver gatewayRateLimitKeyResolver(final ClientIpResolver clientIpResolver) {
        return new RateLimitKeyResolver(clientIpResolver);
    }

    @Bean
    public RateLimitOutageGuard rateLimitOutageGuard(final RateLimitProperties properties,
                                                     final GatewayErrorResponseWriter errorResponseWriter) {
        return new RateLimitOutageGuard(properties.outagePolicy(), errorResponseWriter);
    }

    @Bean
    public GlobalRateLimitFilter globalRateLimitFilter(
            @Qualifier("globalRateLimiter") final FleetRateLimiter fleetRateLimiter,
            final KeyResolver keyResolver,
            final RateLimitProperties rateLimitProperties,
            final FleetRateLimitBudget budget,
            final GatewayErrorResponseWriter errorResponseWriter,
            final RateLimitOutageGuard outageGuard) {
        return new GlobalRateLimitFilter(fleetRateLimiter, keyResolver, rateLimitProperties, budget,
                errorResponseWriter, outageGuard);
    }
}
