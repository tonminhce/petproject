package com.shop.gateway.ratelimit;

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

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        chain = mock(GatewayFilterChain.class);
        properties = new RateLimitProperties(true, 100, 200, 1, 0);
        globalProperties = new GlobalRateLimitProperties(true, 2_000, 4_000, 1);
    }

    @Test
    void allowedRequestContinuesToRouteChain() {
        var filter = new GlobalRateLimitFilter(rateLimiter, properties, globalProperties);
        var exchange = exchange();
        when(rateLimiter.isAllowed("gateway-system", "system"))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(rateLimiter).isAllowed("gateway-system", "system");
    }

    @Test
    void deniedRequestReturns429WithLimiterHeaders() {
        var filter = new GlobalRateLimitFilter(rateLimiter, properties, globalProperties);
        var exchange = exchange();
        when(rateLimiter.isAllowed("gateway-system", "system"))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of(
                        "X-RateLimit-Remaining", "0",
                        "X-RateLimit-Replenish-Rate", "2000"))));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Replenish-Rate"))
                .isEqualTo("2000");
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void disabledGlobalLimiterContinuesWithoutCallingRedis() {
        var disabledProperties = new GlobalRateLimitProperties(false, 2_000, 4_000, 1);
        var filter = new GlobalRateLimitFilter(rateLimiter, properties, disabledProperties);
        var exchange = exchange();
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoRateLimiterCall();
    }

    @Test
    void runsBeforeOtherGatewayFilters() {
        var filter = new GlobalRateLimitFilter(rateLimiter, properties, globalProperties);

        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").build());
    }

    private void verifyNoRateLimiterCall() {
        verify(rateLimiter, never()).isAllowed(any(String.class), any(String.class));
    }
}
