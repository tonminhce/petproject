package com.shop.paymentservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.common.storage.service.StorageObject;
import com.shop.paymentservice.config.TestLiquibaseConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for payment-service full-context integration tests — the
 * property set mirrors notification-service's {@code AbstractIntegrationTest}
 * (same singleton container lifecycle): this service PUBLISHES to Kafka via
 * the outbox relay ({@code PaymentOutboxRelay} → {@code KafkaTemplate}), so
 * the Kafka container is load-bearing as the publish target.
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
 *       are deterministic even if a consumer is added later.</li>
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
 *   <li>{@code shop.payment.provider=mock} pins {@code MockProvider}
 *       (no real PSP) and {@code shop.payment.webhook.secret} gets a fixed
 *       literal so {@code WebhookSignatureVerifier} is deterministic.</li>
 *   <li>Object storage is faked via the nested {@link FakeStorageConfig}
 *       (in-memory map): its {@code ObjectStorageService} bean wins the
 *       {@code @ConditionalOnMissingBean} race in common-storage's
 *       {@code ObjectStorageAutoConfiguration}, so no S3/RustFS endpoint is
 *       ever contacted and {@code ReceiptService} stores receipts in memory.</li>
 * </ul></p>
 *
 * <p>No {@code @ActiveProfiles}: there is no {@code application-test.yml} and
 * the security stub comes from the import — activating an empty profile would
 * be a no-op. Concrete tests may add their own {@code @DynamicPropertySource}
 * methods for service-specific overrides (Spring merges the hierarchy).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class, TestLiquibaseConfig.class,
    AbstractIntegrationTest.FakeStorageConfig.class})
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("payment_test")
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
        registry.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
        // Literal issuer to keep tests independent of any JWT_ISSUER_URI env var;
        // JWT validation itself is stubbed in TestSecurityConfig.
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
        // Payment specifics: deterministic mock PSP + fixed webhook signing secret.
        registry.add("shop.payment.provider", () -> "mock");
        registry.add("shop.payment.webhook.secret", () -> "it-webhook-secret");
    }

    @Autowired
    protected ApplicationContext applicationContext;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStorageConfig {

        @Bean
        ObjectStorageService objectStorageService() {
            return new InMemoryObjectStorageService();
        }
    }

    static final class InMemoryObjectStorageService implements ObjectStorageService {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        private static String qualifiedKey(String bucket, String key) {
            return bucket + "/" + key;
        }

        @Override
        public String defaultBucket() {
            return "payment-test";
        }

        @Override
        public void ensureBucketExists(String bucket) {
        }

        @Override
        public String upload(String key, byte[] content, String contentType) {
            objects.put(qualifiedKey(defaultBucket(), key), content);
            return key;
        }

        @Override
        public String upload(String bucket, String key, InputStream content, long contentLength, String contentType) {
            try (InputStream in = content) {
                objects.put(qualifiedKey(bucket, key), in.readAllBytes());
            } catch (IOException e) {
                throw new StorageException("fake upload failed for " + key, e);
            }
            return key;
        }

        @Override
        public StorageObject download(String key) {
            return download(defaultBucket(), key);
        }

        @Override
        public StorageObject download(String bucket, String key) {
            byte[] content = objects.get(qualifiedKey(bucket, key));
            if (content == null) {
                throw new StorageException("object not found: " + key);
            }
            return StorageObject.of(new ByteArrayInputStream(content), key, null, content.length);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(qualifiedKey(defaultBucket(), key));
        }

        @Override
        public void delete(String key) {
            objects.remove(qualifiedKey(defaultBucket(), key));
        }

        @Override
        public void delete(String bucket, String key) {
            objects.remove(qualifiedKey(bucket, key));
        }

        @Override
        public URL presignedGetUrl(String key, Duration ttl) {
            return presignedUrl(key);
        }

        @Override
        public URL presignedGetUrl(String bucket, String key, Duration ttl) {
            return presignedUrl(key);
        }

        @Override
        public URL presignedPutUrl(String key, String contentType, Duration ttl) {
            return presignedUrl(key);
        }

        private static URL presignedUrl(String key) {
            try {
                return new URL("http://object-storage.test/" + key);
            } catch (IOException e) {
                throw new StorageException("fake presign failed for " + key, e);
            }
        }
    }
}
