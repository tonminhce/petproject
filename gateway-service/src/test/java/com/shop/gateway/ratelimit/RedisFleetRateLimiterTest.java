package com.shop.gateway.ratelimit;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFleetRateLimiterTest {

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

    private FleetRateLimitBudget budget(final int fleetRate, final int fleetBurst,
                                        final int lanes, final int fairShareClients, final int replicas) {
        return FleetRateLimitBudget.from(
                new GlobalRateLimitProperties(true, fleetRate, fleetBurst, 1, lanes, fairShareClients, replicas));
    }

    private RedisFleetRateLimiter limiter(final FleetRateLimitBudget budget) {
        return new RedisFleetRateLimiter(redis, budget);
    }

    private int admitted(final FleetRateLimiter limiter, final String clientKey, final int attempts) {
        var allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.acquire(clientKey).block().allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    private List<String> clientsOnDistinctLanes(final FleetRateLimitBudget budget, final int count) {
        var byLane = new LinkedHashMap<Integer, String>();
        for (int i = 1; byLane.size() < count && i < 5000; i++) {
            var candidate = "ip:198.51.100." + i;
            byLane.putIfAbsent(budget.laneIndex(candidate), candidate);
        }
        if (byLane.size() < count) {
            throw new IllegalStateException("no " + count + " clients on distinct lanes found");
        }
        return List.copyOf(byLane.values());
    }

    @Test
    @DisplayName("a single client cannot consume the fleet budget of the other clients")
    void aSingleClientCannotStarveTheOtherClients() {
        var budget = budget(4, 400, 4, 4, 1);
        var limiter = limiter(budget);
        var flooder = clientsOnDistinctLanes(budget, 2).get(0);
        var victim = clientsOnDistinctLanes(budget, 2).get(1);
        var startedAt = System.nanoTime();

        var flooderAdmitted = admitted(limiter, flooder, 150);
        var victimAdmitted = admitted(limiter, victim, 150);

        var ceiling = 100 + elapsedSeconds(startedAt) + 2;
        assertThat(flooderAdmitted).isBetween(100, ceiling);
        assertThat(victimAdmitted).isBetween(100, ceiling);
    }

    @Test
    @DisplayName("N clients share the fleet budget fairly")
    void nClientsShareTheFleetBudgetFairly() {
        var budget = budget(4, 400, 4, 4, 1);
        var limiter = limiter(budget);
        var clients = clientsOnDistinctLanes(budget, 4);

        var admittedPerClient = new ArrayList<Integer>();
        for (var client : clients) {
            admittedPerClient.add(0);
        }
        var startedAt = System.nanoTime();
        for (int round = 0; round < 120; round++) {
            for (int index = 0; index < clients.size(); index++) {
                if (limiter.acquire(clients.get(index)).block().allowed()) {
                    admittedPerClient.set(index, admittedPerClient.get(index) + 1);
                }
            }
        }

        var elapsed = elapsedSeconds(startedAt);
        var minimum = admittedPerClient.stream().mapToInt(Integer::intValue).min().orElseThrow();
        var maximum = admittedPerClient.stream().mapToInt(Integer::intValue).max().orElseThrow();
        var total = admittedPerClient.stream().mapToInt(Integer::intValue).sum();

        assertThat(minimum).isGreaterThanOrEqualTo(100);
        assertThat(maximum).isLessThanOrEqualTo(100 + elapsed + 2);
        assertThat(total).isLessThanOrEqualTo(400 + (elapsed * 4) + 8);
        assertThat(maximum - minimum).isLessThanOrEqualTo(elapsed + 5);
    }

    @Test
    @DisplayName("the fleet budget is shared by the replicas and not divided by their count")
    void theFleetBudgetIsSharedByTheReplicas() {
        var oneReplica = limiter(budget(4, 400, 4, 4, 1));
        var tenReplicas = limiter(budget(4, 400, 4, 4, 10));
        var clientKey = "ip:203.0.113.7";

        var startedAt = System.nanoTime();
        var admitted = 0;
        for (int i = 0; i < 150; i++) {
            if (oneReplica.acquire(clientKey).block().allowed()) {
                admitted++;
            }
            if (tenReplicas.acquire(clientKey).block().allowed()) {
                admitted++;
            }
        }

        assertThat(admitted).isBetween(100, 100 + elapsedSeconds(startedAt) + 2);
    }

    @Test
    @DisplayName("every key of one bucket carries the same Redis Cluster hash tag")
    void everyKeyOfOneBucketCarriesTheSameHashTag() {
        var keys = RedisFleetRateLimiter.bucketKeys(3, "ip:203.0.113.7");

        assertThat(keys).hasSize(4);
        assertThat(keys.stream().map(RedisFleetRateLimiterTest::hashTag).distinct().toList())
                .containsExactly("{lane-3}");
    }

    @Test
    @DisplayName("an unreachable Redis is reported as an outage instead of an error")
    void anUnreachableRedisIsReportedAsAnOutage() {
        var clientConfiguration = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                        .build())
                .build();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), clientConfiguration);
        connectionFactory.afterPropertiesSet();
        var unreachableRedis = new ReactiveStringRedisTemplate(connectionFactory);
        var limiter = new RedisFleetRateLimiter(unreachableRedis, budget(4, 400, 4, 4, 1));

        var decision = limiter.acquire("ip:203.0.113.7").block();

        assertThat(decision).isNotNull();
        assertThat(decision.isOutage()).isTrue();
        assertThat(decision.allowed()).isFalse();
    }

    private int elapsedSeconds(final long startedAt) {
        final long elapsed = System.nanoTime() - startedAt;
        return (int) Math.max(1L, (elapsed + 999_999_999L) / 1_000_000_000L);
    }

    private static String hashTag(final String key) {
        final int start = key.indexOf('{');
        final int end = key.indexOf('}', start);
        return key.substring(start, end + 1);
    }
}
