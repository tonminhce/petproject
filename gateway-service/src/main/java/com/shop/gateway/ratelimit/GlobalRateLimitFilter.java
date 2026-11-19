package com.shop.gateway.ratelimit;

import com.shop.common.core.exception.ErrorCode;
import com.shop.gateway.filter.GatewayErrorResponseWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Applies one shared capacity bucket before route-specific filters run.
 */
public final class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    private static final String GLOBAL_ROUTE_ID = "gateway-system";
    private static final String GLOBAL_KEY = "system";

    private final RateLimiter<?> rateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final GlobalRateLimitProperties globalRateLimitProperties;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public GlobalRateLimitFilter(final RateLimiter<?> rateLimiter,
                                 final RateLimitProperties rateLimitProperties,
                                 final GlobalRateLimitProperties globalRateLimitProperties,
                                 final GatewayErrorResponseWriter errorResponseWriter) {
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.globalRateLimitProperties = globalRateLimitProperties;
        this.errorResponseWriter = errorResponseWriter;
    }

    public GlobalRateLimitFilter(final RateLimiter<?> rateLimiter,
                                 final RateLimitProperties rateLimitProperties,
                                 final GlobalRateLimitProperties globalRateLimitProperties) {
        this(rateLimiter, rateLimitProperties, globalRateLimitProperties, null);
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!rateLimitProperties.enabled() || !globalRateLimitProperties.enabled()) {
            return chain.filter(exchange);
        }

        return rateLimiter.isAllowed(GLOBAL_ROUTE_ID, GLOBAL_KEY)
                .flatMap(response -> response.isAllowed()
                        ? chain.filter(exchange)
                        : reject(exchange, response.getHeaders()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private Mono<Void> reject(final ServerWebExchange exchange, final Map<String, String> headers) {
        headers.forEach((name, value) -> exchange.getResponse().getHeaders().add(name, value));
        if (errorResponseWriter != null) {
            return errorResponseWriter.write(exchange, ErrorCode.TOO_MANY_REQUESTS);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }
}
