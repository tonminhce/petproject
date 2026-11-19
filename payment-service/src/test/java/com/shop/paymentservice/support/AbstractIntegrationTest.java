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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class, TestLiquibaseConfig.class, AbstractIntegrationTest.FakeStorageConfig.class})
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

    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");

        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");

        registry.add("shop.storage.access-key", () -> "paymenttest");
        registry.add("shop.storage.secret-key", () -> "paymenttest");
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");

        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");

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
