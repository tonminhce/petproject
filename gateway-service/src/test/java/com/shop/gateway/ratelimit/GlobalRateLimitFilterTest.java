package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.core.Ordered;
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

class GlobalRateLimitFilterTest {

    private RateLimiter rateLimiter;
    private GatewayFilterChain chain;
    private RateLimitProperties properties;
    private GlobalRateLimitProperties globalProperties;
    private GatewayErrorResponseWriter errorResponseWriter;
    private RateLimitOutageGuard outageGuard;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        chain = mock(GatewayFilterChain.class);
        properties = new RateLimitProperties(true, 100, 200, 1, RedisOutagePolicy.FAIL_CLOSED);
        globalProperties = new GlobalRateLimitProperties(true, 2_000, 4_000, 1);
        errorResponseWriter = new GatewayErrorResponseWriter(new ObjectMapper());
        outageGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_CLOSED, errorResponseWriter);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void allowedRequestContinuesToRouteChain() {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("gateway-system", "system"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "42"))));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(rateLimiter).isAllowed("gateway-system", "system");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("42");
    }

    @Test
    void deniedRequestReturns429WithTheUniformContract() throws Exception {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("gateway-system", "system"))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of(
                        "X-RateLimit-Remaining", "0",
                        "X-RateLimit-Replenish-Rate", "2000"))));

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
    void redisOutageFailsClosedWithTheServiceUnavailableEnvelope() throws Exception {
        var filter = filter(outageGuard);
        var exchange = exchange();
        when(rateLimiter.isAllowed("gateway-system", "system"))
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
        when(rateLimiter.isAllowed("gateway-system", "system"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "-1"))));

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void disabledGlobalLimiterContinuesWithoutCallingRedis() {
        var disabledProperties = new GlobalRateLimitProperties(false, 2_000, 4_000, 1);
        var filter = new GlobalRateLimitFilter(
                rateLimiter, properties, disabledProperties, errorResponseWriter, outageGuard);
        var exchange = exchange();
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoRateLimiterCall();
    }

    @Test
    void runsBeforeOtherGatewayFilters() {
        var filter = filter(outageGuard);

        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private GlobalRateLimitFilter filter(final RateLimitOutageGuard guard) {
        return new GlobalRateLimitFilter(rateLimiter, properties, globalProperties, errorResponseWriter, guard);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").build());
    }

    private void verifyNoRateLimiterCall() {
        verify(rateLimiter, never()).isAllowed(any(String.class), any(String.class));
    }
}
