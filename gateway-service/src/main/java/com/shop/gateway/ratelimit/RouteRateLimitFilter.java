package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.filter.RateLimitHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class RouteRateLimitFilter implements GatewayFilter {

    private final String routeId;
    private final RateLimiter<?> rateLimiter;
    private final KeyResolver keyResolver;
    private final RateLimitProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final RateLimitOutageGuard outageGuard;

    public RouteRateLimitFilter(final String routeId,
                                 final RateLimiter<?> rateLimiter,
                                 final KeyResolver keyResolver,
                                 final RateLimitProperties properties,
                                 final GatewayErrorResponseWriter errorResponseWriter,
                                 final RateLimitOutageGuard outageGuard) {
        this.routeId = routeId;
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.errorResponseWriter = errorResponseWriter;
        this.outageGuard = outageGuard;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        return keyResolver.resolve(exchange)
                .defaultIfEmpty("")
                .flatMap(key -> key.isBlank()
                        ? errorResponseWriter.writeRateLimited(exchange, retryAfterSeconds())
                        : rateLimiter.isAllowed(routeId, key)
                                .flatMap(response -> decide(exchange, chain, response)));
    }

    public String routeId() {
        return routeId;
    }

    private Mono<Void> decide(final ServerWebExchange exchange,
                              final GatewayFilterChain chain,
                              final RateLimiter.Response response) {
        if (outageGuard.isOutage(response.getHeaders())) {
            return outageGuard.handle(exchange, retryAfterSeconds(), () -> chain.filter(exchange));
        }
        if (!response.isAllowed()) {
            return errorResponseWriter.writeRateLimited(exchange, retryAfterSeconds());
        }
        exchange.getResponse().getHeaders().set(RateLimitHeaders.REMAINING,
                RateLimitHeaders.remainingValue(response.getHeaders()));
        return chain.filter(exchange);
    }

    private int retryAfterSeconds() {
        final int replenishRate = properties.replenishRate();
        final int requestedTokens = properties.requestedTokens();
        return (int) Math.max(1, Math.ceil((double) requestedTokens / replenishRate));
    }
}
