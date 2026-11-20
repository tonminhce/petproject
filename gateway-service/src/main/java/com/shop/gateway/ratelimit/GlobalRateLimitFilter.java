package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.filter.RateLimitHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    private final FleetRateLimiter fleetRateLimiter;
    private final KeyResolver keyResolver;
    private final RateLimitProperties rateLimitProperties;
    private final FleetRateLimitBudget budget;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final RateLimitOutageGuard outageGuard;

    public GlobalRateLimitFilter(final FleetRateLimiter fleetRateLimiter,
                                 final KeyResolver keyResolver,
                                 final RateLimitProperties rateLimitProperties,
                                 final FleetRateLimitBudget budget,
                                 final GatewayErrorResponseWriter errorResponseWriter,
                                 final RateLimitOutageGuard outageGuard) {
        this.fleetRateLimiter = fleetRateLimiter;
        this.keyResolver = keyResolver;
        this.rateLimitProperties = rateLimitProperties;
        this.budget = budget;
        this.errorResponseWriter = errorResponseWriter;
        this.outageGuard = outageGuard;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!rateLimitProperties.enabled() || !budget.enabled()) {
            return chain.filter(exchange);
        }

        return keyResolver.resolve(exchange)
                .defaultIfEmpty("")
                .flatMap(clientKey -> clientKey.isBlank()
                        ? errorResponseWriter.writeRateLimited(exchange, retryAfterSeconds())
                        : fleetRateLimiter.acquire(clientKey)
                                .flatMap(decision -> decide(exchange, chain, decision)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private Mono<Void> decide(final ServerWebExchange exchange,
                              final GatewayFilterChain chain,
                              final FleetRateLimitDecision decision) {
        if (decision.isOutage()) {
            return outageGuard.handle(exchange, retryAfterSeconds(), () -> chain.filter(exchange));
        }
        if (!decision.allowed()) {
            return errorResponseWriter.writeRateLimited(exchange, retryAfterSeconds());
        }
        exchange.getResponse().getHeaders().set(RateLimitHeaders.REMAINING,
                Long.toString(decision.remaining()));
        return chain.filter(exchange);
    }

    private int retryAfterSeconds() {
        final int clientReplenishRate = budget.clientReplenishRate();
        final int requestedTokens = budget.requestedTokens();
        return (int) Math.max(1, Math.ceil((double) requestedTokens / clientReplenishRate));
    }
}
