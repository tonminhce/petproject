package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import com.shop.gateway.constant.ServiceRoute;
import com.shop.gateway.ratelimit.EdgeRateLimitDecision;
import com.shop.gateway.ratelimit.EdgeRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

public final class RateLimitFilter implements GlobalFilter, Ordered {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private enum Scope {
        BACKOFFICE, SEARCH
    }

    private final EdgeRateLimitProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final ClientIpResolver clientIpResolver;
    private final EdgeRateLimiter rateLimiter;
    private final List<String> backofficePrefixes;

    public RateLimitFilter(final EdgeRateLimitProperties properties,
                           final GatewayErrorResponseWriter errorResponseWriter,
                           final ClientIpResolver clientIpResolver,
                           final EdgeRateLimiter rateLimiter) {
        this.properties = properties;
        this.errorResponseWriter = errorResponseWriter;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiter = rateLimiter;
        this.backofficePrefixes = ServiceRoute.backofficeRoutes().map(ServiceRoute::prefix).toList();
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        final String rawPath = exchange.getRequest().getPath().value();
        if (RequestPathGuard.containsMatrixVariable(rawPath)) {
            log.warn("Rejected matrix-variable path in an edge scope: {}", rawPath);
            return errorResponseWriter.write(exchange, ErrorCode.BAD_REQUEST);
        }
        final Scope scope = scopeFor(RequestPathGuard.decoded(rawPath));
        if (scope == null) {
            return chain.filter(exchange);
        }

        final String clientIp = clientIpResolver.resolve(exchange);
        final int requestsPerMinute = scope == Scope.BACKOFFICE
                ? properties.backofficeRequestsPerMinute()
                : properties.searchRequestsPerMinute();
        return rateLimiter.acquire(scope.name(), clientIp, requestsPerMinute)
                .flatMap(decision -> applyDecision(exchange, chain, decision, rawPath));
    }

    private Mono<Void> applyDecision(final ServerWebExchange exchange,
                                      final GatewayFilterChain chain,
                                      final EdgeRateLimitDecision decision,
                                      final String rawPath) {
        if (!decision.allowed()) {
            exchange.getResponse().getHeaders().set(REMAINING_HEADER, "0");
            return errorResponseWriter.write(exchange, ErrorCode.TOO_MANY_REQUESTS);
        }
        exchange.getResponse().getHeaders().set(REMAINING_HEADER, Long.toString(decision.remaining()));
        if (RequestPathGuard.isEncoded(rawPath)) {
            log.warn("Rejected percent-encoded path in a rate-limited scope (attempt still metered): {}", rawPath);
            return errorResponseWriter.write(exchange, ErrorCode.BAD_REQUEST);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FilterOrder.RATE_LIMIT;
    }

    private Scope scopeFor(final String path) {
        if (backofficePrefixes.stream().anyMatch(prefix -> RequestPathMatcher.matchesPrefix(path, prefix))) {
            return Scope.BACKOFFICE;
        }
        if (RequestPathMatcher.matchesPrefix(path, ServiceRoute.SEARCH.prefix())) {
            return Scope.SEARCH;
        }
        return null;
    }

}
