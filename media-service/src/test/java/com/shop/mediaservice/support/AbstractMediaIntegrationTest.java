package com.shop.mediaservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class})
public abstract class AbstractMediaIntegrationTest {

    /** Same name for {@code shop.storage.bucket} and {@code media.bucket} — single source of truth. */
    public static final String BUCKET = "media";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("media_test")
        .withUsername("test")
        .withPassword("test");

    @SuppressWarnings("resource")
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    /**
     * F-042 (P1) — the S3 test double is now the PRODUCTION backend.
     *
     * <p>The suite previously used {@code minio/minio:latest}. That tag no longer
     * exists on Docker Hub ("pull access denied for minio/minio, repository does
     * not exist"), so every media-service integration test - 44 tests across
     * UploadIT, MediaOutboxIT, MediaOutboxRetentionIT, MediaPurgeIT,
     * MediaOutboxClaimConcurrencyIT and ProvisioningIT - failed in the static
     * initializer and could never run, in CI or anywhere else.</p>
     *
     * <p>Using RustFS - the same image and the same credential environment the
     * compose stack runs ({@code rustfs/rustfs:1.0.0-rc.5} with
     * RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY) - fixes the unrunnable suite AND
     * removes the fidelity gap: the tests now exercise the S3 implementation the
     * platform actually ships against, instead of a different product.</p>
     */
    @SuppressWarnings("resource")
    static final GenericContainer<?> rustfs = new GenericContainer<>(
            DockerImageName.parse("rustfs/rustfs:1.0.0-rc.5"))
        .withEnv("RUSTFS_VOLUMES", "/data")
        .withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
        .withEnv("RUSTFS_CONSOLE_ADDRESS", "0.0.0.0:9001")
        .withEnv("RUSTFS_CONSOLE_ENABLE", "true")
        .withEnv("RUSTFS_ACCESS_KEY", "mediatest")
        .withEnv("RUSTFS_SECRET_KEY", "mediatest")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                .forStatusCode(200));

    /** S3 endpoint of the shared RustFS container. */
    public static String rustfsEndpoint() {
        return "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
    }

    static {
        postgres.start();
        kafka.start();
        rustfs.start();
    }

    /** Bootstrap servers of the shared Kafka singleton — ITs assert on real topics. */
    public static String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Liquibase owns the media schema (changelog-001) — schema-by-Hibernate
        // stays off (ddl-auto none) so the changelog is what ITs exercise.
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("shop.storage.endpoint", AbstractMediaIntegrationTest::rustfsEndpoint);
        registry.add("shop.storage.access-key", () -> "mediatest");
        registry.add("shop.storage.secret-key", () -> "mediatest");
        registry.add("shop.storage.bucket", () -> BUCKET);
        registry.add("media.bucket", () -> BUCKET);
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
    }
}
