package com.shop.gateway.config;

import com.shop.gateway.filter.AdminIpAllowlistFilter;
import com.shop.gateway.filter.AdminIpAllowlistProperties;
import com.shop.gateway.filter.AdminRoleGateFilter;
import com.shop.gateway.filter.ClientIpResolver;
import com.shop.gateway.filter.EdgeRateLimitProperties;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.filter.PreAuthRateLimitFilter;
import com.shop.gateway.filter.PreAuthRateLimitProperties;
import com.shop.gateway.filter.RateLimitFilter;
import com.shop.gateway.filter.TrustedProxyPolicy;
import com.shop.gateway.ratelimit.EdgeRateLimiter;
import com.shop.gateway.ratelimit.RateLimitOutageGuard;
import com.shop.gateway.ratelimit.RateLimitProperties;
import com.shop.gateway.ratelimit.RedisEdgeRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class EdgeFiltersConfiguration {

    @Bean
    public TrustedProxyPolicy trustedProxyPolicy(final TrustedProxyProperties properties) {
        return new TrustedProxyPolicy(properties.cidrs(), properties.hops());
    }

    @Bean
    public ClientIpResolver clientIpResolver(final TrustedProxyPolicy trustedProxyPolicy) {
        return new ClientIpResolver(trustedProxyPolicy);
    }

    @Bean
    public GatewayErrorResponseWriter gatewayErrorResponseWriter(final ObjectMapper objectMapper) {
        return new GatewayErrorResponseWriter(objectMapper);
    }

    @Bean
    public AdminIpAllowlistFilter adminIpAllowlistFilter(final AdminIpAllowlistProperties properties,
                                                         final GatewayErrorResponseWriter errorResponseWriter,
                                                         final ClientIpResolver clientIpResolver) {
        return new AdminIpAllowlistFilter(properties, errorResponseWriter, clientIpResolver);
    }

    @Bean
    public EdgeRateLimiter edgeRateLimiter(final ReactiveStringRedisTemplate redis,
                                           final EdgeRateLimitProperties properties) {
        return new RedisEdgeRateLimiter(redis, properties);
    }

    @Bean
    public RateLimitFilter edgeRateLimitFilter(final EdgeRateLimitProperties properties,
                                               final GatewayErrorResponseWriter errorResponseWriter,
                                               final ClientIpResolver clientIpResolver,
                                               final EdgeRateLimiter edgeRateLimiter,
                                               final RateLimitOutageGuard outageGuard) {
        return new RateLimitFilter(properties, errorResponseWriter, clientIpResolver, edgeRateLimiter, outageGuard);
    }

    @Bean
    public PreAuthRateLimitFilter preAuthRateLimitFilter(final PreAuthRateLimitProperties properties,
                                                          final EdgeRateLimitProperties edgeRateLimitProperties,
                                                          final EdgeRateLimiter edgeRateLimiter,
                                                          final ClientIpResolver clientIpResolver,
                                                          final GatewayErrorResponseWriter errorResponseWriter,
                                                          final RateLimitOutageGuard outageGuard) {
        return new PreAuthRateLimitFilter(properties, edgeRateLimitProperties, edgeRateLimiter,
                clientIpResolver, errorResponseWriter, outageGuard);
    }

    @Bean
    public AdminRoleGateFilter adminRoleGateFilter(final GatewayErrorResponseWriter errorResponseWriter) {
        return new AdminRoleGateFilter(errorResponseWriter);
    }
}
