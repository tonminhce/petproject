package com.shop.gateway.resilience;

import com.shop.gateway.filter.FilterOrder;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

public final class DownstreamResilienceFilter implements GlobalFilter, Ordered {

    static final String UNROUTED_BUCKET = "gateway-unrouted";
    static final int BULKHEAD_RETRY_AFTER_SECONDS = 1;

    private final CircuitBreakerProperties circuitBreakerProperties;
    private final BulkheadProperties bulkheadProperties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public DownstreamResilienceFilter(final CircuitBreakerProperties circuitBreakerProperties,
                                      final BulkheadProperties bulkheadProperties,
                                      final CircuitBreakerRegistry circuitBreakerRegistry,
                                      final BulkheadRegistry bulkheadRegistry,
                                      final GatewayErrorResponseWriter errorResponseWriter) {
        this.circuitBreakerProperties = circuitBreakerProperties;
        this.bulkheadProperties = bulkheadProperties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        final String bucket = bucketName(exchange);
        final CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(bucket);
        final Bulkhead bulkhead = bulkheadRegistry.bulkhead(serviceBucket(route));
        return Mono.defer(() -> {
            if (bulkheadProperties.enabled() && !bulkhead.tryAcquirePermission()) {
                return errorResponseWriter.writeRateLimited(exchange, BULKHEAD_RETRY_AFTER_SECONDS);
            }
            if (circuitBreakerProperties.enabled() && !breaker.tryAcquirePermission()) {
                release(bulkhead);
                return errorResponseWriter.writeServiceUnavailable(exchange, retryAfterSeconds());
            }
            final long startedAt = System.nanoTime();
            return chain.filter(exchange)
                    .doOnSuccess(ignored -> recordOutcome(breaker, startedAt, exchange))
                    .doOnError(error -> recordFailure(breaker, startedAt, error))
                    .doFinally(signal -> release(bulkhead));
        });
    }

    @Override
    public int getOrder() {
        return FilterOrder.DOWNSTREAM_RESILIENCE;
    }

    private String bucketName(final ServerWebExchange exchange) {
        final Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? UNROUTED_BUCKET : route.getId();
    }

    private String serviceBucket(final Route route) {
        if (route == null) {
            return UNROUTED_BUCKET;
        }
        final String host = route.getUri().getHost();
        return host == null || host.isBlank() ? route.getId() : "service:" + host;
    }

    private void recordOutcome(final CircuitBreaker breaker, final long startedAt,
                               final ServerWebExchange exchange) {
        final var status = exchange.getResponse().getStatusCode();
        if (status != null && status.is5xxServerError()) {
            breaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS,
                    new DownstreamServerErrorException("downstream responded with " + status));
            return;
        }
        breaker.onSuccess(elapsedNanos(startedAt), TimeUnit.NANOSECONDS);
    }

    private void recordFailure(final CircuitBreaker breaker, final long startedAt, final Throwable error) {
        breaker.onError(elapsedNanos(startedAt), TimeUnit.NANOSECONDS, error);
    }

    private void release(final Bulkhead bulkhead) {
        if (bulkheadProperties.enabled()) {
            bulkhead.onComplete();
        }
    }

    private long elapsedNanos(final long startedAt) {
        return System.nanoTime() - startedAt;
    }

    private int retryAfterSeconds() {
        return (int) Math.max(1, circuitBreakerProperties.waitDurationInOpenState().toSeconds());
    }
}
