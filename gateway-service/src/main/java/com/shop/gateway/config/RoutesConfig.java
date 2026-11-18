package com.shop.gateway.config;

import com.shop.gateway.constant.ServiceRoute;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.ratelimit.RateLimitOutageGuard;
import com.shop.gateway.ratelimit.RateLimitProperties;
import com.shop.gateway.ratelimit.RouteRateLimitFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {

    private final RateLimiter<?> rateLimiter;
    private final KeyResolver keyResolver;
    private final RateLimitProperties rateLimitProperties;
    private final RouteTargetProperties routeTargetProperties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final RateLimitOutageGuard outageGuard;

    public RoutesConfig(@Qualifier("gatewayRateLimiter") final RateLimiter<?> rateLimiter,
                        final KeyResolver keyResolver,
                        final RateLimitProperties rateLimitProperties,
                        final RouteTargetProperties routeTargetProperties,
                        final GatewayErrorResponseWriter errorResponseWriter,
                        final RateLimitOutageGuard outageGuard) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.rateLimitProperties = rateLimitProperties;
        this.routeTargetProperties = routeTargetProperties;
        this.errorResponseWriter = errorResponseWriter;
        this.outageGuard = outageGuard;
    }

    @Bean
    public RouteLocator gatewayRoutes(final RouteLocatorBuilder builder) {
        final var routesBuilder = builder.routes();
        for (final var route : ServiceRoute.values()) {
            routesBuilder.route(route.id(), spec -> {
                final var routeSpec = spec.path(route.path());
                if (rateLimitProperties.enabled()) {
                    routeSpec.filters(filters -> filters.filter(new RouteRateLimitFilter(
                            route.id(), rateLimiter, keyResolver, rateLimitProperties, errorResponseWriter, outageGuard)));
                }
                return routeSpec.uri(routeTargetProperties.resolve(route.serviceName(), route.uri()));
            });
        }
        return routesBuilder.build();
    }
}
