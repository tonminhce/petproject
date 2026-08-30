package com.shop.promotionservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import com.shop.promotionservice.config.TestLiquibaseConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for promotion-service full-context integration tests — the
 * property set mirrors inventory-service's {@code AbstractIntegrationTest}
 * (same event-producing, outbox-publishing shape) but with order-service's
 * SINGLETON container lifecycle.
 *
 * <p>SINGLETON LIFECYCLE — deliberate divergence from inventory's per-class
 * {@code @Container} style: containers are plain static fields started once
 * per JVM in a static initializer and never stopped by the test framework.
 * Rationale (order-service task-13 lesson): per-class container management
 * stops the containers after the first subclass finishes, which kills the
 * mapped ports any shared cached Spring context points at
 * ({@code HikariPool ... Connection refused}). Static singletons are safe
 * under BOTH context-cache scenarios — one shared context or one context per
 * subclass — because every context binds to the same live containers. The
 * surefire fork's exit (plus Testcontainers Ryuk) reaps them.</p>
 *
 * <p>Wiring per service module:
 * <ul>
 *   <li>PostgreSQL + Kafka via Testcontainers, bound through
 *       {@link DynamicPropertySource}. Kafka binds {@code shop.kafka.*}
 *       (common-kafka's KafkaProperties) — NOT {@code spring.kafka.*}; only
 *       the outbox relay produces, there is no consumer.</li>
 *   <li>Schema is owned by Liquibase ({@link TestLiquibaseConfig} supplies the
 *       {@link liquibase.integration.spring.SpringLiquibase} bean);
 *       Hibernate {@code ddl-auto} is pinned to {@code none} so JPA never
 *       races or mutates the changelog-managed schema (prod runs
 *       {@code validate}; nothing here needs it).</li>
 *   <li>JWT decoding is stubbed via {@link TestSecurityConfig} so the
 *       production decoder ({@code @ConditionalOnMissingBean} in
 *       common-security's BaseSecurityConfig — the stub wins) never reaches a
 *       real Keycloak at startup. The literal issuer keeps tests independent
 *       of any JWT_ISSUER_URI env var. Endpoint auth itself is exercised by
 *       the controller slices; ITs are service-layer and skip the filter
 *       chain entirely.</li>
 *   <li>No Redis/cache: promotion-service has no cache starter, so unlike
 *       inventory there is no {@code spring.cache.type} to disable.</li>
 * </ul></p>
 *
 * <p>No {@code @ActiveProfiles}: there is no {@code application-test.yml} and
 * the security stub comes from the import — activating an empty profile would
 * be a no-op. Concrete tests may add their own {@code @DynamicPropertySource}
 * methods for service-specific overrides (Spring merges the hierarchy).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class, TestLiquibaseConfig.class})
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("promotion_test")
        .withUsername("test")
        .withPassword("test");

    @SuppressWarnings("resource")
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        postgres.start();
        kafka.start();
        // No explicit stop: the surefire fork's JVM exit (Ryuk) reaps containers;
        // stopping per-class would break the shared cached context (see class javadoc).
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        // Schema is owned by Liquibase — keep Hibernate from validating/mutating it in tests.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // common-kafka binds shop.kafka.* (NOT spring.kafka.*).
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Literal issuer to keep tests independent of any JWT_ISSUER_URI env var;
        // JWT validation itself is stubbed in TestSecurityConfig.
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
    }

    @Autowired
    protected ApplicationContext applicationContext;
}
