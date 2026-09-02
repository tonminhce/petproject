package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import com.shop.gateway.constant.ServiceRoute;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * D4 — bucket4j per-IP token buckets for the two edge scopes:
 * backoffice prefixes and the search prefix. Other paths are unlimited.
 * Buckets live in-process (single gateway instance, V1); a rejected request
 * gets the fleet 429 envelope plus {@code X-RateLimit-Remaining: 0}, a passed
 * request is tagged with the remaining tokens for debuggability (§4.4).
 *
 * <p>Scope lookup happens on the percent-DECODED path and a percent-encoded
 * request whose decoded form falls into a scope is still metered, then
 * rejected with the fleet 400 envelope (raw &ne; decoded — see
 * {@link RequestPathGuard}; route predicates would decode it to the scoped
 * route, so raw-only matching would let one encoded character bypass the
 * limiter entirely). Matrix-variable paths (N-R1) are rejected before scope
 * lookup — the {@code ;} suffix defeats the prefix match itself.</p>
 */
public final class RateLimitFilter implements GlobalFilter, Ordered {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private enum Scope {
        BACKOFFICE, SEARCH
    }

    private final EdgeRateLimitProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    private final ClientIpResolver clientIpResolver;
    private final List<String> backofficePrefixes;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(final EdgeRateLimitProperties properties,
                           final GatewayErrorResponseWriter errorResponseWriter,
                           final ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.errorResponseWriter = errorResponseWriter;
        this.clientIpResolver = clientIpResolver;
        this.backofficePrefixes = ServiceRoute.backofficeRoutes().map(ServiceRoute::prefix).toList();
        this.buckets = Caffeine.newBuilder().maximumSize(properties.maximumBuckets()).expireAfterAccess(properties.bucketExpiration().toMillis(), TimeUnit.MILLISECONDS).build();
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

        final String key = scope + ":" + clientIpResolver.resolve(exchange);
        final Bucket bucket = buckets.get(key, ignored -> newBucket(scope));
        if (!bucket.tryConsume(1)) {
            exchange.getResponse().getHeaders().set(REMAINING_HEADER, "0");
            return errorResponseWriter.write(exchange, ErrorCode.TOO_MANY_REQUESTS);
        }
        exchange.getResponse().getHeaders().set(REMAINING_HEADER, Long.toString(bucket.getAvailableTokens()));
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
        if (backofficePrefixes.stream().anyMatch(prefix -> matchesPrefix(path, prefix))) {
            return Scope.BACKOFFICE;
        }
        if (matchesPrefix(path, ServiceRoute.SEARCH.prefix())) {
            return Scope.SEARCH;
        }
        return null;
    }

    private static boolean matchesPrefix(final String path, final String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private Bucket newBucket(final Scope scope) {
        final long capacity = scope == Scope.BACKOFFICE
                ? properties.backofficeRequestsPerMinute()
                : properties.searchRequestsPerMinute();
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofMinutes(1))))
                .build();
    }
}
