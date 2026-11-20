package com.shop.gateway.config;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.ratelimit.RateLimitOutageGuard;
import com.shop.gateway.ratelimit.RateLimitProperties;
import com.shop.gateway.ratelimit.RedisOutagePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoutesConfigTest {

    @Test
    void registersRateLimiterOnEveryBackendRoute() {
        var routeLocator = createRoutes(new RateLimitProperties(true, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED));

        var routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).hasSize(30);
        assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).hasSize(1));
    }

    @Test
    void routesTheCartApiToTheOrderService() {
        var routeLocator = createRoutes(new RateLimitProperties(false, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED));

        var routes = routeLocator.getRoutes().collectList().block();

        var cartRoute = routes.stream()
                .filter(route -> route.getId().equals("order-cart"))
                .findFirst();
        assertThat(cartRoute)
                .as("the cart api belongs to the order service and must be reachable through the gateway")
                .isPresent();
        assertThat(cartRoute.get().getUri()).hasToString("http://order-service:8084");
        assertThat(cartRoute.get().getPredicate())
                .as("the cart route must match the cart api path")
                .isNotNull();
        assertThat(cartRoute.get().getPredicate().toString())
                .as("the cart route must match the cart api path")
                .contains("/api/v1/carts");
    }

    @Test
    void canDisableRateLimiterWithoutRemovingRoutes() {
        var routeLocator = createRoutes(new RateLimitProperties(false, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED));

        var routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).hasSize(30);
        assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).isEmpty());
    }

    @Test
    void registersAllTwelveBackofficeRoutesWithTargetUris() {
        var routeLocator = createRoutes(new RateLimitProperties(false, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED));

        var routes = routeLocator.getRoutes().collectList().block();

        var backofficeRoutes = routes.stream()
                .filter(route -> route.getId().startsWith("backoffice-"))
                .toList();
        assertThat(backofficeRoutes).hasSize(12);
        assertThat(backofficeRoutes)
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://product-service:8086");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/products");
                })
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://product-service:8086");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/categories");
                })
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://product-service:8086");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/brands");
                })
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
                })
                .satisfiesOnlyOnce(route -> {
                    assertThat(route.getUri().toString()).isEqualTo("http://media-service:8083");
                    assertThat(route.getPredicate().toString()).contains("/api/v1/backoffice/medias");
                });
    }

    @Test
    void mediaRoutesUsePluralPredicateAndMediaServiceTarget() {
        var routeLocator = createRoutes(new RateLimitProperties(false, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED));

        var routes = routeLocator.getRoutes().collectList().block();

        var mediaRoute = routes.stream()
                .filter(route -> route.getId().equals("media-service"))
                .findFirst().orElseThrow();

        assertThat(mediaRoute.getUri().toString()).isEqualTo("http://media-service:8083");
        assertThat(mediaRoute.getPredicate().toString()).contains("/api/v1/medias");
        assertThat(mediaRoute.getPredicate().toString()).doesNotContain("/api/v1/media/**");
        assertThat(mediaRoute.getPredicate().toString()).doesNotContain("/api/v1/backoffice/medias");
    }

    @Test
    void targetUriOverrideWinsOverDefaultServiceUri() {
        var routeLocator = createRoutes(
                new RateLimitProperties(false, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED),
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
            RateLimitProperties properties) {
        return createRoutes(properties, Map.of());
    }

    private org.springframework.cloud.gateway.route.RouteLocator createRoutes(
            RateLimitProperties properties,
            Map<String, String> targetOverrides) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class,
                    () -> new PathRoutePredicateFactory(new WebFluxProperties()));
            context.refresh();
            var errorResponseWriter = new GatewayErrorResponseWriter(new ObjectMapper());
            return new RoutesConfig(mock(RateLimiter.class), mock(KeyResolver.class), properties,
                    new RouteTargetProperties(targetOverrides),
                    errorResponseWriter,
                    new RateLimitOutageGuard(RedisOutagePolicy.FAIL_CLOSED, errorResponseWriter))
                    .gatewayRoutes(new RouteLocatorBuilder(context));
        }
    }
}
