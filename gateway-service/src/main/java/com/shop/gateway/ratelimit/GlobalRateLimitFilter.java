package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.GatewayErrorResponseWriter;
import com.shop.gateway.filter.RateLimitHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    private static final String GLOBAL_ROUTE_ID = "gateway-system";
    private static final String GLOBAL_KEY = "system";

    private final RateLimiter<?> rateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final GlobalRateLimitProperties globalRateLimitProperties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final RateLimitOutageGuard outageGuard;

    public GlobalRateLimitFilter(final RateLimiter<?> rateLimiter,
                                 final RateLimitProperties rateLimitProperties,
                                 final GlobalRateLimitProperties globalRateLimitProperties,
                                 final GatewayErrorResponseWriter errorResponseWriter,
                                 final RateLimitOutageGuard outageGuard) {
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.globalRateLimitProperties = globalRateLimitProperties;
        this.errorResponseWriter = errorResponseWriter;
        this.outageGuard = outageGuard;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!rateLimitProperties.enabled() || !globalRateLimitProperties.enabled()) {
            return chain.filter(exchange);
        }

        return rateLimiter.isAllowed(GLOBAL_ROUTE_ID, GLOBAL_KEY)
                .flatMap(response -> decide(exchange, chain, response));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
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
        final int replenishRate = globalRateLimitProperties.replenishRate();
        final int requestedTokens = globalRateLimitProperties.requestedTokens();
        return (int) Math.max(1, Math.ceil((double) requestedTokens / replenishRate));
    }
}
