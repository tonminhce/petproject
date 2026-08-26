package com.shop.gateway.config;

import com.shop.gateway.constant.ServiceRoute;
import com.shop.gateway.ratelimit.RateLimitProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {

    private final RateLimiter rateLimiter;
    private final KeyResolver keyResolver;
    private final RateLimitProperties rateLimitProperties;

    public RoutesConfig(@Qualifier("gatewayRateLimiter") final RateLimiter<?> rateLimiter,
                        final KeyResolver keyResolver,
                        final RateLimitProperties rateLimitProperties) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Bean
    public RouteLocator gatewayRoutes(final RouteLocatorBuilder builder) {
        final var routesBuilder = builder.routes();
        for (final var route : ServiceRoute.values()) {
            routesBuilder.route(route.id(), spec -> {
                final var routeSpec = spec.path(route.path());
                if (rateLimitProperties.enabled()) {
                    routeSpec.filters(filters -> filters.requestRateLimiter(config -> config
                            .setRateLimiter(rateLimiter)
                            .setKeyResolver(keyResolver)
                            .setDenyEmptyKey(true)
                            .setEmptyKeyStatus("429")));
                }
                return routeSpec.uri(route.uri());
            });
        }
        return routesBuilder.build();
    }
}
