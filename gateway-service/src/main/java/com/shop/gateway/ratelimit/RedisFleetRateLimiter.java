package com.shop.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class RedisFleetRateLimiter implements FleetRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisFleetRateLimiter.class);

    private static final String KEY_PREFIX = "gateway:fleet-rate-limit:";
    private static final String LANE_TAG_PREFIX = "lane-";

    static final String FLEET_BUCKET_SCRIPT = """
            local laneCapacity = tonumber(ARGV[1])
            local laneRate = tonumber(ARGV[2])
            local clientCapacity = tonumber(ARGV[3])
            local clientRate = tonumber(ARGV[4])
            local requested = tonumber(ARGV[5])
            local laneTtl = tonumber(ARGV[6])
            local clientTtl = tonumber(ARGV[7])

            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) + (tonumber(clock[2]) / 1000000)

            local laneTokens = tonumber(redis.call('get', KEYS[1]))
            local laneStamp = tonumber(redis.call('get', KEYS[2]))
            if laneTokens == nil then laneTokens = laneCapacity end
            if laneStamp == nil then laneStamp = now end
            local laneFilled = math.min(laneCapacity, laneTokens + (math.max(0, now - laneStamp) * laneRate))

            local clientTokens = tonumber(redis.call('get', KEYS[3]))
            local clientStamp = tonumber(redis.call('get', KEYS[4]))
            if clientTokens == nil then clientTokens = clientCapacity end
            if clientStamp == nil then clientStamp = now end
            local clientFilled = math.min(clientCapacity, clientTokens + (math.max(0, now - clientStamp) * clientRate))

            local allowed = 0
            if laneFilled >= requested and clientFilled >= requested then
                allowed = 1
                laneFilled = laneFilled - requested
                clientFilled = clientFilled - requested
            end

            redis.call('setex', KEYS[1], laneTtl, laneFilled)
            redis.call('setex', KEYS[2], laneTtl, now)
            redis.call('setex', KEYS[3], clientTtl, clientFilled)
            redis.call('setex', KEYS[4], clientTtl, now)

            return {allowed, math.floor(laneFilled), math.floor(clientFilled)}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> FLEET_BUCKET =
            new DefaultRedisScript<>(FLEET_BUCKET_SCRIPT, List.class);

    private final ReactiveStringRedisTemplate redis;
    private final FleetRateLimitBudget budget;

    public RedisFleetRateLimiter(final ReactiveStringRedisTemplate redis, final FleetRateLimitBudget budget) {
        this.redis = redis;
        this.budget = budget;
        log.info("Fleet limiter active: fleet={} perSecond over {} lanes, client share={} perSecond, declared replicas={}, per replica share={}",
                budget.fleetReplenishRate(), budget.lanes(), budget.clientReplenishRate(),
                budget.replicas(), budget.perReplicaReplenishRate());
    }

    @Override
    public Mono<FleetRateLimitDecision> acquire(final String clientKey) {
        final int lane = budget.laneIndex(clientKey);
        final List<String> keys = bucketKeys(lane, clientKey);
        return redis.execute(FLEET_BUCKET, keys,
                        Long.toString(budget.laneBurstCapacity()),
                        Integer.toString(budget.laneReplenishRate()),
                        Long.toString(budget.clientBurstCapacity()),
                        Integer.toString(budget.clientReplenishRate()),
                        Integer.toString(budget.requestedTokens()),
                        Long.toString(budget.laneTtlSeconds()),
                        Long.toString(budget.clientTtlSeconds()))
                .next()
                .map(this::toDecision)
                .defaultIfEmpty(FleetRateLimitDecision.outage())
                .onErrorResume(error -> {
                    log.error("Fleet rate limiter unavailable for lane {}", lane, error);
                    return Mono.just(FleetRateLimitDecision.outage());
                });
    }

    static List<String> bucketKeys(final int lane, final String clientKey) {
        final String hashTag = "{" + LANE_TAG_PREFIX + lane + "}";
        final String clientId = UUID.nameUUIDFromBytes(clientKey.getBytes(StandardCharsets.UTF_8)).toString();
        return List.of(
                KEY_PREFIX + hashTag + ".lane.tokens",
                KEY_PREFIX + hashTag + ".lane.timestamp",
                KEY_PREFIX + hashTag + ".client." + clientId + ".tokens",
                KEY_PREFIX + hashTag + ".client." + clientId + ".timestamp");
    }

    private FleetRateLimitDecision toDecision(final List<?> result) {
        if (result == null || result.size() < 2) {
            return FleetRateLimitDecision.outage();
        }
        final boolean allowed = asLong(result.get(0)) == 1L;
        final long laneRemaining = asLong(result.get(1));
        final long clientRemaining = result.size() > 2 ? asLong(result.get(2)) : laneRemaining;
        final long remaining = Math.min(laneRemaining, clientRemaining);
        return allowed ? FleetRateLimitDecision.allowed(remaining) : FleetRateLimitDecision.rejected();
    }

    private long asLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return 0L;
    }
}
