package com.shop.gateway.filter;

import com.shop.gateway.ratelimit.EdgeRateLimitDecision;
import com.shop.gateway.ratelimit.EdgeRateLimiter;
import com.shop.gateway.ratelimit.RateLimitOutageGuard;
import com.shop.gateway.ratelimit.RedisOutagePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreAuthRateLimitFilterTest {

    private EdgeRateLimiter rateLimiter;
    private WebFilterChain chain;
    private GatewayErrorResponseWriter errorResponseWriter;
    private RateLimitOutageGuard outageGuard;
    private ClientIpResolver ipResolver;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(EdgeRateLimiter.class);
        chain = mock(WebFilterChain.class);
        errorResponseWriter = new GatewayErrorResponseWriter(new ObjectMapper());
        outageGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_CLOSED, errorResponseWriter);
        ipResolver = new ClientIpResolver(new TrustedProxyPolicy(List.of(), 0));
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private PreAuthRateLimitFilter filter(final PreAuthRateLimitProperties properties,
                                          final RateLimitOutageGuard guard) {
        return new PreAuthRateLimitFilter(properties, new EdgeRateLimitProperties(true, 10, 60),
                rateLimiter, ipResolver, errorResponseWriter, guard);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders").build());
    }

    @Test
    void everyRequestIsMeteredOnThePreAuthenticationEdgeBudget() {
        when(rateLimiter.acquire(any(), any(), anyInt())).thenReturn(Mono.just(EdgeRateLimitDecision.allowed(9)));
        var filter = filter(new PreAuthRateLimitProperties(true, 3000), outageGuard);
        var exchange = exchange();

        filter.filter(exchange, chain).block();

        verify(rateLimiter).acquire(eq(PreAuthRateLimitFilter.SCOPE), anyString(), eq(3000));
        verify(chain).filter(exchange);
    }

    @Test
    void anExhaustedBudgetIsRejectedWithTheRateLimitContract() throws Exception {
        when(rateLimiter.acquire(any(), any(), anyInt())).thenReturn(Mono.just(EdgeRateLimitDecision.rejected()));
        var filter = filter(new PreAuthRateLimitProperties(true, 3000), outageGuard);
        var exchange = exchange();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void anOutageFailsClosedWithTheServiceUnavailableEnvelope() {
        when(rateLimiter.acquire(any(), any(), anyInt())).thenReturn(Mono.just(EdgeRateLimitDecision.outage()));
        var filter = filter(new PreAuthRateLimitProperties(true, 3000), outageGuard);
        var exchange = exchange();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    void anOutageFailsOpenWhenThePolicyAllowsIt() {
        var openGuard = new RateLimitOutageGuard(RedisOutagePolicy.FAIL_OPEN, errorResponseWriter);
        when(rateLimiter.acquire(any(), any(), anyInt())).thenReturn(Mono.just(EdgeRateLimitDecision.outage()));
        var filter = filter(new PreAuthRateLimitProperties(true, 3000), openGuard);
        var exchange = exchange();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void aDisabledBudgetLetsEveryRequestThrough() {
        var filter = filter(new PreAuthRateLimitProperties(false, 3000), outageGuard);
        var exchange = exchange();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());
    }

    @Test
    void aDisabledEdgeLimiterDisablesThePreAuthenticationMetering() {
        var filter = new PreAuthRateLimitFilter(new PreAuthRateLimitProperties(true, 3000),
                new EdgeRateLimitProperties(false, 10, 60), rateLimiter, ipResolver, errorResponseWriter, outageGuard);
        var exchange = exchange();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());
    }

    @Test
    void aPreflightRequestIsNotMetered() {
        var filter = filter(new PreAuthRateLimitProperties(true, 3000), outageGuard);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.options("/api/v1/orders").build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());
    }
}
