package com.shop.gateway.filter;

import com.shop.gateway.ratelimit.EdgeRateLimitDecision;
import com.shop.gateway.ratelimit.EdgeRateLimiter;
import com.shop.gateway.ratelimit.RateLimitOutageGuard;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class PreAuthRateLimitFilter {

    static final String SCOPE = "PREAUTH";

    private final PreAuthRateLimitProperties properties;
    private final EdgeRateLimitProperties edgeRateLimitProperties;
    private final EdgeRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final RateLimitOutageGuard outageGuard;

    public PreAuthRateLimitFilter(final PreAuthRateLimitProperties properties,
                                   final EdgeRateLimitProperties edgeRateLimitProperties,
                                   final EdgeRateLimiter rateLimiter,
                                   final ClientIpResolver clientIpResolver,
                                   final GatewayErrorResponseWriter errorResponseWriter,
                                   final RateLimitOutageGuard outageGuard) {
        this.properties = properties;
        this.edgeRateLimitProperties = edgeRateLimitProperties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.errorResponseWriter = errorResponseWriter;
        this.outageGuard = outageGuard;
    }

    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        if (!enabled() || isPreflight(exchange)) {
            return chain.filter(exchange);
        }
        final String clientIp = clientIpResolver.resolve(exchange);
        return rateLimiter.acquire(SCOPE, clientIp, properties.requestsPerMinute())
                .flatMap(decision -> decide(exchange, chain, decision));
    }

    private boolean enabled() {
        return properties.enabled() && edgeRateLimitProperties.enabled();
    }

    private Mono<Void> decide(final ServerWebExchange exchange,
                              final WebFilterChain chain,
                              final EdgeRateLimitDecision decision) {
        if (decision.isOutage()) {
            return outageGuard.handle(exchange, retryAfterSeconds(), () -> chain.filter(exchange));
        }
        if (!decision.allowed()) {
            return errorResponseWriter.writeRateLimited(exchange, retryAfterSeconds());
        }
        return chain.filter(exchange);
    }

    private boolean isPreflight(final ServerWebExchange exchange) {
        return HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod());
    }

    private int retryAfterSeconds() {
        final int requestsPerMinute = properties.requestsPerMinute();
        return (int) Math.max(1, Math.ceil(60.0d / requestsPerMinute));
    }
}
