package com.shop.gateway.ratelimit;

import com.shop.gateway.filter.EdgeRateLimitProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge rate limit is fleet-wide (F-034).
 *
 * <p>The buckets used to live in the gateway JVM, so every replica metered the
 * client against its own budget: with four replicas a single client could make
 * 4 x 10 backoffice requests per minute — the limit that is supposed to
 * protect the backoffice was four times weaker than configured.</p>
 *
 * <p>This test drives two independent limiter instances (two gateway
 * replicas) against one Redis and proves that they share a single bucket.</p>
 */
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

    /**
     * The buckets live in Redis, so every case starts with an empty database —
     * otherwise the cases would share the budget of the same client.
     */
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

        // the client is at the limit after the requests below
        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block()).isNotNull();
        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block()).isNotNull();

        // the second replica continues the SAME budget, it does not start a new one
        var third = replicaB.acquire("BACKOFFICE", "203.0.113.7", 3).block();
        assertThat(third).isNotNull();
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();

        // the budget is exhausted for every replica
        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block().allowed()).isFalse();
        assertThat(replicaB.acquire("BACKOFFICE", "203.0.113.7", 3).block().allowed()).isFalse();
    }

    @Test
    @DisplayName("the scope and the client address isolate the buckets")
    void scopeAndClientAreIsolated() {
        var properties = new EdgeRateLimitProperties(true, 1, 60);
        var limiter = limiter(properties);

        assertThat(limiter.acquire("BACKOFFICE", "203.0.113.7", 1).block().allowed()).isTrue();
        // the budget of this client and scope is spent
        assertThat(limiter.acquire("BACKOFFICE", "203.0.113.7", 1).block().allowed()).isFalse();
        // another client has its own budget
        assertThat(limiter.acquire("BACKOFFICE", "203.0.113.8", 1).block().allowed()).isTrue();
        // another scope has its own budget
        assertThat(limiter.acquire("SEARCH", "203.0.113.7", 1).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("the bucket refills at the configured rate")
    void theBucketRefillsAtTheConfiguredRate() throws InterruptedException {
        var properties = new EdgeRateLimitProperties(true, 60, 60);
        var limiter = limiter(properties);

        // 60 per minute = one token per second. The bucket is drained UNTIL it
        // denies a request: a fixed number of requests is not enough, because
        // the bucket refills while it is drained and a request that crosses a
        // second boundary gets a token back (the refill works on whole
        // seconds). A fixed count made this test fail under load - the
        // machine was busy enough for the drain to cross a second boundary.
        boolean denied = false;
        for (int i = 0; i < 500 && !denied; i++) {
            denied = !limiter.acquire("SEARCH", "198.51.100.5", 60).block().allowed();
        }
        assertThat(denied).as("the bucket must be empty").isTrue();

        // the refill is one token per second -> after 1.5s at least one token
        // is back
        Thread.sleep(1_500L);
        var afterRefill = limiter.acquire("SEARCH", "198.51.100.5", 60).block();
        assertThat(afterRefill.allowed()).isTrue();
    }
}
