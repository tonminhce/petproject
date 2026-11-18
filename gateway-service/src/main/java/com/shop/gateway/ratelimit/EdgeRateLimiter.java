package com.shop.gateway.ratelimit;

import reactor.core.publisher.Mono;

public interface EdgeRateLimiter {

    Mono<EdgeRateLimitDecision> acquire(String scope, String clientIp, int requestsPerMinute);
}
