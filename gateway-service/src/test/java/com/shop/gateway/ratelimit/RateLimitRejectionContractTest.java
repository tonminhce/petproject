package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.ClientIpResolver;
import com.shop.gateway.filter.EdgeRateLimitProperties;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.filter.RateLimitFilter;
import com.shop.gateway.filter.TrustedProxyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitRejectionContractTest {

    private static final String BACKOFFICE_PATH = "/api/v1/backoffice/products";

    private GatewayErrorResponseWriter errorResponseWriter;
    private RateLimitOutageGuard outageGuard;
    private RateLimiter rateLimiter;
    private KeyResolver keyResolver;
    private EdgeRateLimiter edgeRateLimiter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        errorResponseWriter = new GatewayErrorResponseWriter(new ObjectMapper());
        outageGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_CLOSED, errorResponseWriter);
        rateLimiter = mock(RateLimiter.class);
        keyResolver = mock(KeyResolver.class);
        edgeRateLimiter = mock(EdgeRateLimiter.class);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        when(keyResolver.resolve(any())).thenReturn(Mono.just("user:user-42"));
    }

    @Test
    void everyLimiterLayerRejectsWithTheSameStatusBodyAndHeaders() throws Exception {
        when(rateLimiter.isAllowed(any(), any()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of("X-RateLimit-Remaining", "0"))));
        when(edgeRateLimiter.acquire(any(), any(), anyInt()))
                .thenReturn(Mono.just(EdgeRateLimitDecision.rejected()));

        var routeLayer = rejectWithRouteLayer();
        var globalLayer = rejectWithGlobalLayer();
        var edgeLayer = rejectWithEdgeLayer();

        assertRejectionContract(routeLayer);
        assertRejectionContract(globalLayer);
        assertRejectionContract(edgeLayer);
        assertThat(bodyShape(globalLayer)).isEqualTo(bodyShape(routeLayer));
        assertThat(bodyShape(edgeLayer)).isEqualTo(bodyShape(routeLayer));
    }

    @Test
    void everyLimiterLayerFailsTheSameWayWhenRedisIsUnreachable() throws Exception {
        when(rateLimiter.isAllowed(any(), any()))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "-1"))));
        when(edgeRateLimiter.acquire(any(), any(), anyInt()))
                .thenReturn(Mono.just(EdgeRateLimitDecision.outage()));

        var routeLayer = routeLayerExchange();
        var globalLayer = globalLayerExchange();
        var edgeLayer = edgeLayerExchange();

        new RouteRateLimitFilter("product-service", rateLimiter, keyResolver,
                rateLimitProperties(), errorResponseWriter, outageGuard).filter(routeLayer, chain).block();
        new GlobalRateLimitFilter(rateLimiter, rateLimitProperties(),
                new GlobalRateLimitProperties(true, 2_000, 4_000, 1), errorResponseWriter, outageGuard)
                .filter(globalLayer, chain).block();
        edgeFilter().filter(edgeLayer, chain).block();

        assertOutageContract(routeLayer);
        assertOutageContract(globalLayer);
        assertOutageContract(edgeLayer);
        assertThat(bodyShape(globalLayer)).isEqualTo(bodyShape(routeLayer));
        assertThat(bodyShape(edgeLayer)).isEqualTo(bodyShape(routeLayer));
    }

    @Test
    void everyLimiterLayerFailsOpenWhenThePolicyAllowsIt() {
        var openGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_OPEN, errorResponseWriter);
        when(rateLimiter.isAllowed(any(), any()))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "-1"))));
        when(edgeRateLimiter.acquire(any(), any(), anyInt()))
                .thenReturn(Mono.just(EdgeRateLimitDecision.outage()));

        var routeLayer = routeLayerExchange();
        var globalLayer = globalLayerExchange();
        var edgeLayer = edgeLayerExchange();

        new RouteRateLimitFilter("product-service", rateLimiter, keyResolver,
                rateLimitProperties(), errorResponseWriter, openGuard).filter(routeLayer, chain).block();
        new GlobalRateLimitFilter(rateLimiter, rateLimitProperties(),
                new GlobalRateLimitProperties(true, 2_000, 4_000, 1), errorResponseWriter, openGuard)
                .filter(globalLayer, chain).block();
        new RateLimitFilter(new EdgeRateLimitProperties(true, 10, 60), errorResponseWriter,
                new ClientIpResolver(new TrustedProxyPolicy(List.of(), 0)), edgeRateLimiter, openGuard)
                .filter(edgeLayer, chain).block();

        assertThat(routeLayer.getResponse().getStatusCode()).isNull();
        assertThat(globalLayer.getResponse().getStatusCode()).isNull();
        assertThat(edgeLayer.getResponse().getStatusCode()).isNull();
        assertThat(routeLayer.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(globalLayer.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(edgeLayer.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    private RateLimitProperties rateLimitProperties() {
        return new RateLimitProperties(true, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED);
    }

    private RateLimitFilter edgeFilter() {
        return new RateLimitFilter(new EdgeRateLimitProperties(true, 10, 60), errorResponseWriter,
                new ClientIpResolver(new TrustedProxyPolicy(List.of(), 0)), edgeRateLimiter, outageGuard);
    }

    private MockServerWebExchange rejectWithRouteLayer() {
        var exchange = routeLayerExchange();
        new RouteRateLimitFilter("product-service", rateLimiter, keyResolver,
                rateLimitProperties(), errorResponseWriter, outageGuard).filter(exchange, chain).block();
        return exchange;
    }

    private MockServerWebExchange rejectWithGlobalLayer() {
        var exchange = globalLayerExchange();
        new GlobalRateLimitFilter(rateLimiter, rateLimitProperties(),
                new GlobalRateLimitProperties(true, 2_000, 4_000, 1), errorResponseWriter, outageGuard)
                .filter(exchange, chain).block();
        return exchange;
    }

    private MockServerWebExchange rejectWithEdgeLayer() {
        var exchange = edgeLayerExchange();
        edgeFilter().filter(exchange, chain).block();
        return exchange;
    }

    private MockServerWebExchange routeLayerExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").build());
    }

    private MockServerWebExchange globalLayerExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders").build());
    }

    private MockServerWebExchange edgeLayerExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get(BACKOFFICE_PATH).build());
    }

    private void assertRejectionContract(final MockServerWebExchange exchange) throws Exception {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Remaining")).containsExactly("0");
        assertThat(Integer.parseInt(exchange.getResponse().getHeaders().getFirst("Retry-After"))).isPositive();
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");
        assertThat(json.get("message").asText()).isEqualTo("Too many requests. Please slow down.");
        assertThat(json.get("path").asText()).isNotBlank();
    }

    private void assertOutageContract(final MockServerWebExchange exchange) throws Exception {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Remaining")).containsExactly("0");
        assertThat(Integer.parseInt(exchange.getResponse().getHeaders().getFirst("Retry-After"))).isPositive();
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0503");
        assertThat(json.get("message").asText()).isEqualTo("The rate limiter is unavailable. Please retry later.");
    }

    private List<String> bodyShape(final MockServerWebExchange exchange) throws Exception {
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        return json.propertyNames().stream().sorted().toList();
    }
}
