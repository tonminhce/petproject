package com.shop.authservice.repository;

import com.shop.authservice.entity.PasswordResetToken;
import com.shop.authservice.entity.User;
import com.shop.authservice.repository.UserRepository;
import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingAutoConfiguration.class,
    LiquibaseAutoConfiguration.class,
    PasswordResetTokenConsumptionTest.LiquibaseTestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetTokenConsumptionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_reset_token")
            .withUsername("auth")
            .withPassword("auth");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LiquibaseTestConfig {

        @Bean
        SpringLiquibase springLiquibase(DataSource dataSource,
                                         @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.yaml}") String changeLog) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog(changeLog);
            return liquibase;
        }
    }

    @Autowired
    private PasswordResetTokenRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private String tokenHash;

    @BeforeEach
    void seedToken() {
        tokenHash = "hash-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = userRepository.saveAndFlush(User.builder()
                .fullName("Alice Wonder")
                .username("alice-" + suffix)
                .email("alice-" + suffix + "@example.com")
                .gender("female")
                .phone("09" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100_000_000L)))
                .build());
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(owner.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusSeconds(3600))
                .used(false)
                .build();
        repository.saveAndFlush(token);
    }

    @Test
    @DisplayName("a second redemption of the same token does not consume it again")
    void aTokenIsConsumedOnlyOnce() {
        assertThat(repository.consumeIfUnused(tokenHash)).isEqualTo(1);
        assertThat(repository.consumeIfUnused(tokenHash)).isZero();
    }

    @Test
    @DisplayName("two concurrent redemptions - exactly one of them wins")
    void twoConcurrentRedemptionsHaveExactlyOneWinner() throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (repository.consumeIfUnused(tokenHash) == 1) {
                        winners.incrementAndGet();
                    } else {
                        losers.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(winners.get()).isEqualTo(1);
        assertThat(losers.get()).isEqualTo(1);
        assertThat(repository.findByTokenHashAndUsedFalse(tokenHash)).isEmpty();
    }
}
