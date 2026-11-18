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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class})
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("product_test")
        .withUsername("test")
        .withPassword("test");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

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
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
    }

    @Autowired
    protected ApplicationContext applicationContext;

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
