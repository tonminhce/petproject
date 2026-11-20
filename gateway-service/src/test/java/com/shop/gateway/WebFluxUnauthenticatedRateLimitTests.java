package com.shop.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@SpringBootTest(
        classes = {GatewayServiceApplication.class, WebFluxUnauthenticatedRateLimitTests.RedisProps.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFluxUnauthenticatedRateLimitTests {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
        flushBuckets();
    }

    private void flushBuckets() {
        var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        ReactiveRedisConnectionFactory connectionFactory = factory;
        var connection = connectionFactory.getReactiveConnection();
        connection.serverCommands().flushDb().block();
        factory.destroy();
    }

    @Test
    void theFirstUnauthenticatedRequestIsUnauthorizedAndNotRateLimited() {
        client.get().uri("/api/v1/backoffice/ratings")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void anUnauthenticatedFloodIsEventuallyRateLimitedAfterTheBudgetIsExhausted() {
        client.get().uri("/api/v1/backoffice/ratings")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/v1/backoffice/ratings")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/v1/backoffice/ratings")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("ERR-0429");
    }

    @Test
    void anUnauthenticatedRequestWithABogusTokenIsRateLimitedAfterTheBudgetIsExhausted() {
        client.get().uri("/api/v1/backoffice/ratings")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/v1/backoffice/ratings")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/v1/backoffice/ratings")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @TestConfiguration
    static class RedisProps {

        @Bean
        DynamicPropertyRegistrar redisProperties() {
            return registry -> {
                registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        () -> "http://localhost:9999/realms/test/protocol/openid-connect/certs");
                registry.add("gateway.keycloak-issuer-uri", () -> "http://localhost:9999/realms/test");
                registry.add("gateway.cors-allowed-origin-patterns", () -> "http://localhost:3000");
                registry.add("gateway.rate-limit.enabled", () -> "false");
                registry.add("gateway.rate-limit.global.enabled", () -> "false");
                registry.add("gateway.edge-rate-limit.backoffice-requests-per-minute", () -> "100");
                registry.add("gateway.pre-auth-rate-limit.requests-per-minute", () -> "2");
                registry.add("spring.data.redis.host", REDIS::getHost);
                registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
            };
        }
    }
}
