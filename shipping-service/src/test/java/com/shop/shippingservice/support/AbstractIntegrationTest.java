package com.shop.shippingservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import com.shop.shippingservice.config.TestClockConfig;
import com.shop.shippingservice.config.TestLiquibaseConfig;
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
 * Base class for shipping-service full-context integration tests — the
 * property set mirrors notification-service's {@code AbstractIntegrationTest}
 * (same singleton container lifecycle) and keeps BOTH containers: this
 * service actually CONSUMES from Kafka ({@code OrderEventConsumer}), so the
 * Kafka container is load-bearing, not just an outbox-relay target.
 *
 * <p>SINGLETON LIFECYCLE — containers are plain static fields started once
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
 *       (common-kafka's KafkaProperties) — NOT {@code spring.kafka.*} —
 *       plus {@code shop.kafka.consumer.auto-offset-reset=earliest} so ITs
 *       are deterministic: the listener starts at the earliest offset of a
 *       fresh container topic instead of racing whatever the production
 *       {@code latest} default would skip.</li>
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
 *       chain entirely (webhook endpoints sit on the public-paths allowlist
 *       anyway).</li>
 *   <li>The production UTC {@link java.time.Clock} is overridden by
 *       {@link TestClockConfig}'s mutable fixed clock
 *       ({@code AtomicReference<Instant>} holder + {@code @Primary} Clock
 *       reading it): the reconciliation scheduler and webhook/service layers
 *       see a frozen instant an IT can advance deterministically instead of
 *       racing wall-clock time.</li>
 *   <li>Carrier wiring is pinned to {@code manual} so exactly one
 *       {@code CarrierAdapter} ({@code ManualCarrierAdapter}) is active —
 *       this also shields the pin from any {@code SHOP_SHIPPING_CARRIER}
 *       env var leaking through the application.yml placeholder.</li>
 *   <li>The GHN webhook secret is pinned to a fixed literal via relaxed
 *       binding on the {@code shop.shipping.webhook.secrets} map, so flow
 *       ITs can HMAC-sign carrier webhook bodies without reading any env
 *       var.</li>
 * </ul></p>
 *
 * <p>No {@code @ActiveProfiles}: there is no {@code application-test.yml} and
 * the security stub comes from the import — activating an empty profile would
 * be a no-op. Concrete tests may add their own {@code @DynamicPropertySource}
 * methods for service-specific overrides (Spring merges the hierarchy).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class, TestLiquibaseConfig.class, TestClockConfig.class})
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("shipping_test")
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
        // common-kafka binds shop.kafka.* (NOT spring.kafka.*); this service consumes,
        // so pin earliest for deterministic IT runs (prod default is latest).
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
        // Literal issuer to keep tests independent of any JWT_ISSUER_URI env var;
        // JWT validation itself is stubbed in TestSecurityConfig.
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
        // Exactly one adapter (Manual) regardless of env; see class javadoc.
        registry.add("shop.shipping.carrier", () -> "manual");
        // Fixed GHN webhook secret (map key survives relaxed binding verbatim).
        registry.add("shop.shipping.webhook.secrets.GHN", () -> "ghn-it-secret");
    }

    @Autowired
    protected ApplicationContext applicationContext;
}
