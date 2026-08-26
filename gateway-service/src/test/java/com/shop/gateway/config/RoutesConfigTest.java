package com.shop.gateway.config;

import com.shop.gateway.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoutesConfigTest {

    @Test
    void registersRateLimiterOnEveryBackendRoute() {
        var rateLimiter = mock(RateLimiter.class);
        var keyResolver = mock(KeyResolver.class);
        var rateLimiterFactory = new RequestRateLimiterGatewayFilterFactory(rateLimiter, keyResolver);
        var routeLocator = createRoutes(rateLimiterFactory, rateLimiter, keyResolver,
                new RateLimitProperties(true, 100, 200, 1, 0));

        var routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).hasSize(15);
        assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).hasSize(1));
    }

    @Test
    void canDisableRateLimiterWithoutRemovingRoutes() {
        var rateLimiter = mock(RateLimiter.class);
        var keyResolver = mock(KeyResolver.class);
        var rateLimiterFactory = new RequestRateLimiterGatewayFilterFactory(rateLimiter, keyResolver);
        var routeLocator = createRoutes(rateLimiterFactory, rateLimiter, keyResolver,
                new RateLimitProperties(false, 100, 200, 1, 0));

        var routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).hasSize(15);
        assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).isEmpty());
    }

    private org.springframework.cloud.gateway.route.RouteLocator createRoutes(
            RequestRateLimiterGatewayFilterFactory rateLimiterFactory,
            RateLimiter rateLimiter,
            KeyResolver keyResolver,
            RateLimitProperties properties) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RequestRateLimiterGatewayFilterFactory.class, () -> rateLimiterFactory);
            context.registerBean(PathRoutePredicateFactory.class,
                    () -> new PathRoutePredicateFactory(new WebFluxProperties()));
            context.refresh();
            return new RoutesConfig(rateLimiter, keyResolver, properties)
                    .gatewayRoutes(new RouteLocatorBuilder(context));
        }
    }
}
