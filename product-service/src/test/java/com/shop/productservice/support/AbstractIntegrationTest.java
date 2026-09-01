package com.shop.productservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for full-context integration tests.
 *
 * <p>Spins up PostgreSQL and Kafka via Testcontainers, wires them into Spring
 * through {@link DynamicPropertySource}, and forces the {@code test} profile.
 * Concrete tests extend this class and get a ready-to-use
 * {@link ApplicationContext} without repeating container setup.</p>
 *
 * <p>Redis is intentionally disabled here — outbox + DB assertions do not need
 * a cache, and avoiding Redis keeps the test container set minimal. Tests that
 * need caching must override the {@code spring.cache.type} property locally.</p>
 *
 * <p>JWT decoding is stubbed via {@link TestSecurityConfig} so the production
 * decoder (which fetches JWKS from Keycloak) does not try to reach a real
 * identity provider at startup.</p>
 *
 * <p>Containers are singletons booted once per JVM (order-service pattern).
 * Per-class {@code @Testcontainers} start/stop would break Spring's cached
 * {@code ApplicationContext}: classes sharing a context cache key reuse a
 * context bound to the previous class's stopped containers, and every test
 * then fails with {@code Connection refused} against the dead mapped port
 * (GitHub issue #1). Never stopping the containers keeps every cached context
 * pointed at live containers; the surefire fork's JVM exit reaps them.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class})
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("product_test")
        .withUsername("test")
        .withPassword("test");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    /** Bootstrap servers of the shared Kafka singleton — ITs assert on real topics (media base precedent). */
    public static String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    static {
        postgres.start();
        kafka.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            postgres.stop();
            kafka.stop();
        }));
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.cache.type", () -> "none");
        // Literal issuer to keep tests independent of any JWT_ISSUER_URI env var;
        // JWT validation itself is stubbed in TestSecurityConfig.
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
    }

    @Autowired
    protected ApplicationContext applicationContext;

    /**
     * Rule 1 (test-cache-isolation fleet spec): every IT base that boots a
     * cache-capable context MUST reset cache state in {@code @BeforeEach}. This
     * base runs cache-free ({@code spring.cache.type=none}, no Redis
     * container), so the clear below is currently a synchronous no-op — it
     * exists so any future cache-capable subclass inherits deterministic
     * per-test cache state for all four {@code @Cacheable} names in
     * {@code CacheConfig}. {@code required = false} + null guards keep it
     * harmless even when no {@code CacheManager} bean exists; with immediate
     * cache writes each clear is synchronous, visible before the test body.
     */
    @Autowired(required = false)
    private CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        if (cacheManager == null) {
            return;
        }
        for (String name : List.of("product", "productBySlug", "category", "brand")) {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
    }
}
