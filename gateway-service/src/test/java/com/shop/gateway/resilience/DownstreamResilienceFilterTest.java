package com.shop.gateway.resilience;

import com.shop.gateway.filter.FilterOrder;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownstreamResilienceFilterTest {

    private GatewayFilterChain chain;
    private GatewayErrorResponseWriter errorResponseWriter;

    @BeforeEach
    void setUp() {
        chain = mock(GatewayFilterChain.class);
        errorResponseWriter = new GatewayErrorResponseWriter(new ObjectMapper());
    }

    private CircuitBreakerProperties breakerProperties() {
        return new CircuitBreakerProperties(true, 50, 4, 4, Duration.ofMillis(200), 1, Duration.ofSeconds(5));
    }

    private BulkheadProperties bulkheadProperties(final int maxConcurrentCalls) {
        return new BulkheadProperties(true, maxConcurrentCalls);
    }

    private CircuitBreakerRegistry breakerRegistry(final CircuitBreakerProperties properties) {
        return CircuitBreakerRegistry.of(DownstreamResilienceConfiguration.circuitBreakerConfig(properties));
    }

    private BulkheadRegistry bulkheadRegistry(final BulkheadProperties properties) {
        return BulkheadRegistry.of(DownstreamResilienceConfiguration.bulkheadConfig(properties));
    }

    private DownstreamResilienceFilter filter(final CircuitBreakerProperties breaker,
                                              final BulkheadProperties bulkhead,
                                              final CircuitBreakerRegistry registry,
                                              final BulkheadRegistry bulkheadRegistry) {
        return new DownstreamResilienceFilter(breaker, bulkhead, registry, bulkheadRegistry, errorResponseWriter);
    }

    private MockServerWebExchange routedExchange(final String path, final String routeId, final String host) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        var route = Route.async()
                .id(routeId)
                .uri(URI.create("http://" + host + ":8080"))
                .predicate(exchange -> true)
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").build());
    }

    @Test
    void theBreakerOpensAfterTheConfiguredFailureThreshold() {
        var breaker = breakerProperties();
        var registry = breakerRegistry(breaker);
        var filter = filter(breaker, bulkheadProperties(10), registry, bulkheadRegistry(bulkheadProperties(10)));
        when(chain.filter(any())).thenReturn(Mono.error(new IllegalStateException("downstream down")));

        for (int i = 0; i < 4; i++) {
            filter.filter(exchange(), chain).onErrorResume(error -> Mono.empty()).block();
        }

        var rejected = exchange();
        filter.filter(rejected, chain).block();

        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        verify(chain, times(4)).filter(any(ServerWebExchange.class));
    }

    @Test
    void theBreakerRecoversOnTheHalfOpenProbe() throws InterruptedException {
        var breaker = breakerProperties();
        var registry = breakerRegistry(breaker);
        var filter = filter(breaker, bulkheadProperties(10), registry, bulkheadRegistry(bulkheadProperties(10)));
        when(chain.filter(any())).thenReturn(Mono.error(new IllegalStateException("downstream down")));
        for (int i = 0; i < 4; i++) {
            filter.filter(exchange(), chain).onErrorResume(error -> Mono.empty()).block();
        }
        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(300);
        when(chain.filter(any())).thenReturn(Mono.empty());
        var probe = exchange();
        filter.filter(probe, chain).block();

        assertThat(probe.getResponse().getStatusCode()).isNull();
        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aBulkheadRejectionDoesNotConsumeAHalfOpenProbe() throws Exception {
        var breaker = breakerProperties();
        var registry = breakerRegistry(breaker);
        var filter = filter(breaker, bulkheadProperties(1), registry, bulkheadRegistry(bulkheadProperties(1)));
        when(chain.filter(any())).thenReturn(Mono.error(new IllegalStateException("downstream down")));
        for (int i = 0; i < 4; i++) {
            filter.filter(exchange(), chain).onErrorResume(error -> Mono.empty()).block();
        }
        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(300);
        var inFlight = Sinks.<Void>empty();
        when(chain.filter(any())).thenReturn(inFlight.asMono());
        filter.filter(exchange(), chain).subscribe();

        for (int i = 0; i < 10; i++) {
            var rejected = exchange();
            filter.filter(rejected, chain).block();
            assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET).getState())
                .isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET)
                .getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(0);

        inFlight.tryEmitEmpty();
    }

    @Test
    void theBulkheadRejectsTheExcessConcurrencyWithABoundedResponse() throws Exception {
        var breaker = breakerProperties();
        var filter = filter(breaker, bulkheadProperties(1), breakerRegistry(breaker), bulkheadRegistry(bulkheadProperties(1)));
        var inFlight = Sinks.<Void>empty();
        when(chain.filter(any())).thenReturn(inFlight.asMono());

        filter.filter(exchange(), chain).subscribe();

        var rejected = exchange();
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getStatusCode().is5xxServerError()).isFalse();
        assertThat(rejected.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        var json = new ObjectMapper().readTree(rejected.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");

        inFlight.tryEmitEmpty();
    }

    @Test
    void theBulkheadPermitIsReleasedWhenTheCallCompletes() {
        var breaker = breakerProperties();
        var filter = filter(breaker, bulkheadProperties(1), breakerRegistry(breaker), bulkheadRegistry(bulkheadProperties(1)));
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange(), chain).block();
        filter.filter(exchange(), chain).block();

        verify(chain, times(2)).filter(any(ServerWebExchange.class));
    }

    @Test
    void aDownstreamServerErrorCountsAsAFailure() {
        var breaker = breakerProperties();
        var registry = breakerRegistry(breaker);
        var filter = filter(breaker, bulkheadProperties(10), registry, bulkheadRegistry(bulkheadProperties(10)));
        when(chain.filter(any())).thenAnswer(invocation -> {
            var exchange = (ServerWebExchange) invocation.getArgument(0);
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return Mono.empty();
        });

        for (int i = 0; i < 4; i++) {
            filter.filter(exchange(), chain).block();
        }

        assertThat(registry.circuitBreaker(DownstreamResilienceFilter.UNROUTED_BUCKET).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void theResilienceLayerRunsAfterTheRoleGate() {
        var breaker = breakerProperties();
        var filter = filter(breaker, bulkheadProperties(10), breakerRegistry(breaker), bulkheadRegistry(bulkheadProperties(10)));

        assertThat(filter.getOrder()).isEqualTo(FilterOrder.DOWNSTREAM_RESILIENCE);
        assertThat(filter.getOrder()).isGreaterThan(FilterOrder.ADMIN_ROLE_GATE);
    }

    @Test
    void aDisabledBulkheadLetsEveryRequestThrough() {
        var breaker = breakerProperties();
        var filter = filter(breaker, new BulkheadProperties(false, 1),
                breakerRegistry(breaker), bulkheadRegistry(new BulkheadProperties(false, 1)));
        when(chain.filter(any())).thenReturn(Mono.empty());

        var first = exchange();
        var second = exchange();
        filter.filter(first, chain).block();
        filter.filter(second, chain).block();

        assertThat(first.getResponse().getStatusCode()).isNull();
        assertThat(second.getResponse().getStatusCode()).isNull();
        verify(chain, times(2)).filter(any(ServerWebExchange.class));
    }

    @Test
    void twoRoutesToTheSameServiceShareOneBulkhead() {
        var breaker = breakerProperties();
        var filter = filter(breaker, bulkheadProperties(1), breakerRegistry(breaker), bulkheadRegistry(bulkheadProperties(1)));
        var inFlight = Sinks.<Void>empty();
        when(chain.filter(any())).thenReturn(inFlight.asMono());

        filter.filter(routedExchange("/api/v1/orders", "order-service", "order-service"), chain).subscribe();

        var rejected = routedExchange("/api/v1/carts/me/items", "cart-service", "order-service");
        filter.filter(rejected, chain).block();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        inFlight.tryEmitEmpty();
    }

    @Test
    void routesToDifferentServicesDoNotShareABulkhead() {
        var breaker = breakerProperties();
        var filter = filter(breaker, bulkheadProperties(1), breakerRegistry(breaker), bulkheadRegistry(bulkheadProperties(1)));
        var inFlight = Sinks.<Void>empty();
        when(chain.filter(any())).thenReturn(inFlight.asMono());

        filter.filter(routedExchange("/api/v1/orders", "order-service", "order-service"), chain).subscribe();

        var other = routedExchange("/api/v1/products", "product-service", "product-service");
        filter.filter(other, chain).block();

        assertThat(other.getResponse().getStatusCode()).isNull();

        inFlight.tryEmitEmpty();
    }
}
