package com.shop.mediaservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
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

    @SuppressWarnings("resource")
    static final MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
        .withUserName("mediatest")
        .withPassword("mediatest");

    static {
        postgres.start();
        kafka.start();
        minio.start();
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
        registry.add("shop.storage.endpoint", minio::getS3URL);
        registry.add("shop.storage.access-key", minio::getUserName);
        registry.add("shop.storage.secret-key", minio::getPassword);
        registry.add("shop.storage.bucket", () -> BUCKET);
        registry.add("media.bucket", () -> BUCKET);
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
    }
}
