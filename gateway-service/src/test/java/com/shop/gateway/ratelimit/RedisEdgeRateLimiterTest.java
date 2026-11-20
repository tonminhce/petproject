package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.EdgeRateLimitProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisEdgeRateLimiterTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @BeforeEach
    void flushBuckets() {
        redis.getConnectionFactory().getReactiveConnection()
                .serverCommands().flushDb()
                .block();
    }

    private RedisEdgeRateLimiter limiter(final EdgeRateLimitProperties properties) {
        return new RedisEdgeRateLimiter(redis, properties);
    }

    @Test
    @DisplayName("two replicas meter the client against ONE shared budget")
    void twoReplicasShareOneBucket() {
        var properties = new EdgeRateLimitProperties(true, 3, 60);
        var replicaA = limiter(properties);
        var replicaB = limiter(properties);

        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block()).isNotNull();
        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block()).isNotNull();

        var third = replicaB.acquire("BACKOFFICE", "203.0.113.7", 3).block();
        assertThat(third).isNotNull();
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();

        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block().allowed()).isFalse();
        assertThat(replicaB.acquire("BACKOFFICE", "203.0.113.7", 3).block().allowed()).isFalse();
    }

    @Test
    @DisplayName("the scope and the client address isolate the buckets")
    void scopeAndClientAreIsolated() {
        var properties = new EdgeRateLimitProperties(true, 1, 60);
        var limiter = limiter(properties);

        assertThat(limiter.acquire("BACKOFFICE", "203.0.113.7", 1).block().allowed()).isTrue();
        assertThat(limiter.acquire("BACKOFFICE", "203.0.113.7", 1).block().allowed()).isFalse();
        assertThat(limiter.acquire("BACKOFFICE", "203.0.113.8", 1).block().allowed()).isTrue();
        assertThat(limiter.acquire("SEARCH", "203.0.113.7", 1).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("the bucket refills at the configured rate")
    void theBucketRefillsAtTheConfiguredRate() throws InterruptedException {
        var properties = new EdgeRateLimitProperties(true, 60, 60);
        var limiter = limiter(properties);

        boolean denied = false;
        for (int i = 0; i < 500 && !denied; i++) {
            denied = !limiter.acquire("SEARCH", "198.51.100.5", 60).block().allowed();
        }
        assertThat(denied).as("the bucket must be empty").isTrue();

        Thread.sleep(1_500L);
        var afterRefill = limiter.acquire("SEARCH", "198.51.100.5", 60).block();
        assertThat(afterRefill.allowed()).isTrue();
    }

    @Test
    @DisplayName("the refill is computed from the Redis clock and never from the gateway clock")
    void theRefillIsComputedFromTheRedisClock() {
        assertThat(RedisEdgeRateLimiter.TOKEN_BUCKET_SCRIPT).contains("redis.call('TIME')");
        assertThat(RedisEdgeRateLimiter.TOKEN_BUCKET_SCRIPT).doesNotContain("now = tonumber(ARGV");
    }

    @Test
    @DisplayName("a bucket stored in the past refills against the Redis clock")
    void aBucketStoredInThePastRefillsAgainstTheRedisClock() {
        var properties = new EdgeRateLimitProperties(true, 60, 60);
        var limiter = limiter(properties);
        var redisNow = redis.getConnectionFactory().getReactiveConnection()
                .serverCommands().time(java.util.concurrent.TimeUnit.SECONDS).block();

        var bucketKeys = RedisEdgeRateLimiter.bucketKeys("SEARCH", "198.51.100.9");
        redis.opsForValue().set(bucketKeys.get(0), "0").block();
        redis.opsForValue().set(bucketKeys.get(1), Long.toString(redisNow - 60)).block();

        var decision = limiter.acquire("SEARCH", "198.51.100.9", 60).block();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(59);
    }

    @Test
    @DisplayName("both keys of one bucket carry the same Redis Cluster hash tag")
    void bothKeysOfABucketShareTheSameHashTag() {
        var keys = RedisEdgeRateLimiter.bucketKeys("BACKOFFICE", "203.0.113.7");

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isEqualTo("gateway:edge-rate-limit:{BACKOFFICE.203.0.113.7}:tokens");
        assertThat(keys.get(1)).isEqualTo("gateway:edge-rate-limit:{BACKOFFICE.203.0.113.7}:timestamp");
        assertThat(hashTag(keys.get(0))).isEqualTo(hashTag(keys.get(1)));
        assertThat(hashTag(keys.get(0))).isEqualTo("{BACKOFFICE.203.0.113.7}");
    }

    @Test
    @DisplayName("the hash tag isolates the buckets of different scopes and clients")
    void theHashTagIsolatesScopesAndClients() {
        var backoffice = RedisEdgeRateLimiter.bucketKeys("BACKOFFICE", "203.0.113.7");
        var search = RedisEdgeRateLimiter.bucketKeys("SEARCH", "203.0.113.7");
        var otherClient = RedisEdgeRateLimiter.bucketKeys("BACKOFFICE", "203.0.113.8");

        assertThat(hashTag(backoffice.get(0))).isNotEqualTo(hashTag(search.get(0)));
        assertThat(hashTag(backoffice.get(0))).isNotEqualTo(hashTag(otherClient.get(0)));
    }

    private static String hashTag(final String key) {
        final int start = key.indexOf('{');
        final int end = key.indexOf('}', start);
        return key.substring(start, end + 1);
    }

    @Test
    @DisplayName("an unreachable Redis is reported as an outage instead of an error")
    void anUnreachableRedisIsReportedAsAnOutage() {
        var clientConfiguration = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                        .build())
                .build();
        var connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), clientConfiguration);
        connectionFactory.afterPropertiesSet();
        var unreachableRedis = new ReactiveStringRedisTemplate(connectionFactory);
        var limiter = new RedisEdgeRateLimiter(unreachableRedis, new EdgeRateLimitProperties(true, 10, 60));

        var decision = limiter.acquire("SEARCH", "198.51.100.9", 10).block();

        assertThat(decision).isNotNull();
        assertThat(decision.isOutage()).isTrue();
        assertThat(decision.allowed()).isFalse();
    }
}
