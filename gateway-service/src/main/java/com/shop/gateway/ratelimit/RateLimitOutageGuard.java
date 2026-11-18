package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.filter.RateLimitHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Supplier;

public final class RateLimitOutageGuard {

    private final RedisOutagePolicy policy;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public RateLimitOutageGuard(final RedisOutagePolicy policy, final GatewayErrorResponseWriter errorResponseWriter) {
        this.policy = policy;
        this.errorResponseWriter = errorResponseWriter;
    }

    public boolean isOutage(final Map<String, String> limiterHeaders) {
        final String remaining = limiterHeaders == null ? null : limiterHeaders.get(RateLimitHeaders.REMAINING);
        return remaining != null && remaining.startsWith("-");
    }

    public Mono<Void> handle(final ServerWebExchange exchange,
                              final int retryAfterSeconds,
                              final Supplier<Mono<Void>> continuation) {
        if (policy.failsOpen()) {
            exchange.getResponse().getHeaders().set(RateLimitHeaders.REMAINING, "0");
            return continuation.get();
        }
        return errorResponseWriter.writeServiceUnavailable(exchange, retryAfterSeconds);
    }
}
