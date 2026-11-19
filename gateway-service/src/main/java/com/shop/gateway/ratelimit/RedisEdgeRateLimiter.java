package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.EdgeRateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * The fleet-wide edge rate limiter (F-034).
 *
 * <p>The bucket lives in Redis, so every gateway replica meters the same
 * client against the same budget. The in-process bucket4j cache that was used
 * before multiplied the effective limit by the replica count — with four
 * replicas a single client could make 4 x 10 backoffice requests per minute,
 * which is exactly the abuse the backoffice limit exists to stop.</p>
 *
 * <p>The bucket keeps the token-bucket semantics of the previous in-process
 * implementation: the capacity is the configured number of requests per
 * minute and the refill rate is that number per minute. The refill rate is
 * therefore fractional (10 rpm = 0.1667 tokens/s), which is why the bucket is
 * implemented by a script instead of the Spring Cloud Gateway limiter (whose
 * replenish rate is an integer number of tokens per second).</p>
 *
 * <p>The script is atomic: read, refill, consume and write happen in one
 * Redis round trip, so two replicas cannot both consume the last token.</p>
 */
public final class RedisEdgeRateLimiter implements EdgeRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisEdgeRateLimiter.class);

    private static final String KEY_PREFIX = "gateway:edge-rate-limit:";

    /**
     * The token bucket.
     *
     * <p>ARGV: capacity, rate (tokens per second), now (epoch seconds),
     * requested tokens. Returns {allowed, remaining}.</p>
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local stored = redis.call('get', KEYS[1])
            local refreshed = redis.call('get', KEYS[2])
            local tokens = capacity
            local last = now
            if stored ~= false then tokens = tonumber(stored) end
            if refreshed ~= false then last = tonumber(refreshed) end

            local delta = math.max(0, now - last)
            local filled = math.min(capacity, tokens + (delta * rate))
            local allowed = 0
            if filled >= requested then
                allowed = 1
                filled = filled - requested
            end

            local ttl = math.ceil((capacity / rate) * 2)
            if ttl > 0 then
                redis.call('setex', KEYS[1], ttl, filled)
                redis.call('setex', KEYS[2], ttl, now)
            end

            return {allowed, math.floor(filled)}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, List.class);

    private final ReactiveStringRedisTemplate redis;
    private final EdgeRateLimitProperties properties;

    public RedisEdgeRateLimiter(final ReactiveStringRedisTemplate redis,
                                 final EdgeRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Mono<EdgeRateLimitDecision> acquire(final String scope, final String clientIp, final int requestsPerMinute) {
        final double rate = requestsPerMinute / 60.0d;
        final long now = Instant.now().getEpochSecond();
        final List<String> keys = List.of(
                KEY_PREFIX + scope + ":" + clientIp + ":tokens",
                KEY_PREFIX + scope + ":" + clientIp + ":timestamp");
        return redis.execute(TOKEN_BUCKET, keys,
                        Integer.toString(requestsPerMinute),
                        Double.toString(rate),
                        Long.toString(now),
                        "1")
                .next()
                .map(this::toDecision)
                // A Redis outage is NOT reported as "too many requests": the
                // error propagates, so the gateway answers with a server error
                // exactly like the two Redis-backed tiers above do. Hiding it
                // behind a 429 would tell the caller to slow down while the
                // real cause is the shared limiter being unavailable.
                .doOnError(error -> log.error("Edge rate limiter failed for scope {} - the budget of this "
                        + "client cannot be metered", scope, error));
    }

    private EdgeRateLimitDecision toDecision(final List<?> result) {
        if (result == null || result.size() < 2) {
            // A script that returned nothing is treated as a rejection: the
            // budget cannot be proven, so the request does not pass.
            return EdgeRateLimitDecision.rejected();
        }
        final boolean allowed = asLong(result.get(0)) == 1L;
        final long remaining = asLong(result.get(1));
        return allowed ? EdgeRateLimitDecision.allowed(remaining) : EdgeRateLimitDecision.rejected();
    }

    private long asLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        return 0L;
    }

    /**
     * The configured limit of a scope, used by the filter to keep the
     * request budget in one place.
     */
    public int requestsPerMinute(final String scope) {
        return "SEARCH".equals(scope)
                ? properties.searchRequestsPerMinute()
                : properties.backofficeRequestsPerMinute();
    }
}
