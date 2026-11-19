package com.shop.gateway.ratelimit;

import reactor.core.publisher.Mono;

/**
 * The edge rate limiter (F-034).
 *
 * <p>The edge scopes — the backoffice prefixes and the search prefix — are
 * metered per client IP. The implementation MUST be fleet-wide: an
 * in-process bucket multiplies the effective limit by the replica count, so
 * with four gateway replicas a single client could make 4 x 10 backoffice
 * requests per minute.</p>
 */
public interface EdgeRateLimiter {

    /**
     * Consumes one token for the given scope and client.
     *
     * @param scope             the edge scope, e.g. {@code BACKOFFICE} or {@code SEARCH}
     * @param clientIp          the resolved client address
     * @param requestsPerMinute the budget of the scope, fleet-wide
     * @return the decision of the attempt
     */
    Mono<EdgeRateLimitDecision> acquire(String scope, String clientIp, int requestsPerMinute);
}
