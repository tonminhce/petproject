package com.shop.gateway.ratelimit;

import reactor.core.publisher.Mono;

public interface FleetRateLimiter {

    Mono<FleetRateLimitDecision> acquire(String clientKey);
}
