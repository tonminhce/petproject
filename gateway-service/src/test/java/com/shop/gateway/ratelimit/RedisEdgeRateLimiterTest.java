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
}
