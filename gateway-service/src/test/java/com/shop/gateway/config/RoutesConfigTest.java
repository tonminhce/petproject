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

import java.util.Map;

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

        assertThat(routes).hasSize(26);
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

        assertThat(routes).hasSize(26);
        assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).isEmpty());
    }

    @Test
    void registersAllNineBackofficeRoutesWithTargetUris() {
        var rateLimiter = mock(RateLimiter.class);
        var keyResolver = mock(KeyResolver.class);
        var rateLimiterFactory = new RequestRateLimiterGatewayFilterFactory(rateLimiter, keyResolver);
        var routeLocator = createRoutes(rateLimiterFactory, rateLimiter, keyResolver,
                new RateLimitProperties(false, 100, 200, 1, 0));

        var routes = routeLocator.getRoutes().collectList().block();

        var backofficeRoutes = routes.stream()
                .filter(route -> route.getId().startsWith("backoffice-"))
                .toList();
        assertThat(backofficeRoutes).hasSize(9);
        assertThat(backofficeRoutes)
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://tax-service:8091");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/tax-classes");
                })
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://rating-service:8089");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/ratings");
                })
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://search-service:8094");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/search");
                });
    }

    @Test
    void targetUriOverrideWinsOverDefaultServiceUri() {
        var rateLimiter = mock(RateLimiter.class);
        var keyResolver = mock(KeyResolver.class);
        var rateLimiterFactory = new RequestRateLimiterGatewayFilterFactory(rateLimiter, keyResolver);
        var routeLocator = createRoutes(rateLimiterFactory, rateLimiter, keyResolver,
                new RateLimitProperties(false, 100, 200, 1, 0),
                Map.of("rating-service", "http://localhost:12345"));

        var routes = routeLocator.getRoutes().collectList().block();

        var overridden = routes.stream()
                .filter(route -> route.getId().equals("backoffice-ratings"))
                .findFirst().orElseThrow();
        var untouched = routes.stream()
                .filter(route -> route.getId().equals("backoffice-products"))
                .findFirst().orElseThrow();
        assertThat(overridden.getUri().toString()).isEqualTo("http://localhost:12345");
        assertThat(untouched.getUri().toString()).isEqualTo("http://product-service:8086");
    }

    private org.springframework.cloud.gateway.route.RouteLocator createRoutes(
            RequestRateLimiterGatewayFilterFactory rateLimiterFactory,
            RateLimiter rateLimiter,
            KeyResolver keyResolver,
            RateLimitProperties properties) {
        return createRoutes(rateLimiterFactory, rateLimiter, keyResolver, properties, Map.of());
    }

    private org.springframework.cloud.gateway.route.RouteLocator createRoutes(
            RequestRateLimiterGatewayFilterFactory rateLimiterFactory,
            RateLimiter rateLimiter,
            KeyResolver keyResolver,
            RateLimitProperties properties,
            Map<String, String> targetOverrides) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RequestRateLimiterGatewayFilterFactory.class, () -> rateLimiterFactory);
            context.registerBean(PathRoutePredicateFactory.class,
                    () -> new PathRoutePredicateFactory(new WebFluxProperties()));
            context.refresh();
            return new RoutesConfig(rateLimiter, keyResolver, properties,
                    new RouteTargetProperties(targetOverrides))
                    .gatewayRoutes(new RouteLocatorBuilder(context));
        }
    }
}
