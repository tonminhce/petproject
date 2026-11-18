package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteRateLimitFilterTest {

    private RateLimiter rateLimiter;
    private KeyResolver keyResolver;
    private GatewayFilterChain chain;
    private GatewayErrorResponseWriter errorResponseWriter;
    private RateLimitOutageGuard outageGuard;
    private RateLimitProperties properties;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        keyResolver = mock(KeyResolver.class);
        chain = mock(GatewayFilterChain.class);
        errorResponseWriter = new GatewayErrorResponseWriter(new ObjectMapper());
        outageGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_CLOSED, errorResponseWriter);
        properties = new RateLimitProperties(true, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED);
        when(chain.filter(any())).thenReturn(Mono.empty());
        when(keyResolver.resolve(any())).thenReturn(Mono.just("user:user-42"));
    }

    @Test
    void allowedRequestContinuesTheChainAndCarriesTheRemainingHeader() {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("product-service", "user:user-42"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "17"))));

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Remaining")).containsExactly("17");
    }

    @Test
    void deniedRequestReturns429WithTheUniformContract() throws Exception {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("product-service", "user:user-42"))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of("X-RateLimit-Remaining", "0"))));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Remaining")).containsExactly("0");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");
        assertThat(json.get("message").asText()).isEqualTo("Too many requests. Please slow down.");
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void emptyKeyIsDeniedWithTheSameContract() throws Exception {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(keyResolver.resolve(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");
        verify(rateLimiter, never()).isAllowed(any(String.class), any(String.class));
    }

    @Test
    void redisOutageFailsClosedWithTheServiceUnavailableEnvelope() throws Exception {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("product-service", "user:user-42"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "-1"))));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Remaining")).containsExactly("0");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("code").asText()).isEqualTo("ERR-0503");
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void redisOutageFailsOpenWhenThePolicyAllowsIt() {
        var openGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_OPEN, errorResponseWriter);
        var filter = filter(openGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("product-service", "user:user-42"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "-1"))));

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void theLimiterIsCalledWithTheRouteIdAndTheResolvedKey() {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("product-service", "user:user-42"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "1"))));

        filter.filter(exchange, chain).block();

        verify(rateLimiter).isAllowed("product-service", "user:user-42");
        assertThat(filter.routeId()).isEqualTo("product-service");
    }

    private RouteRateLimitFilter filter(final RateLimitOutageGuard guard) {
        return new RouteRateLimitFilter(
                "product-service", rateLimiter, keyResolver, properties, errorResponseWriter, guard);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").build());
    }
}
