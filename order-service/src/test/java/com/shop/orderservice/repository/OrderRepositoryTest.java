package com.shop.orderservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.constant.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingAutoConfiguration.class, LiquibaseAutoConfiguration.class, TestLiquibaseConfig.class})
class OrderRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_repo_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TestEntityManager em;
    @Autowired private OrderRepository repo;

    private final UUID alice = UUID.randomUUID();

    @Test
    void findByUserId_returnsOnlyAliceOrders() {
        var order1 = persistOrder(alice, OrderStatus.PENDING);
        persistOrder(UUID.randomUUID(), OrderStatus.PENDING);

        var result = repo.findByUserIdOrderByCreatedAtDesc(alice, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Order::getId).containsExactly(order1.getId());
    }

    @Test
    void findByStatus_filtersCorrectly() {
        var pending = persistOrder(alice, OrderStatus.PENDING);
        persistOrder(alice, OrderStatus.CONFIRMED);

        var result = repo.findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Order::getId).containsExactly(pending.getId());
    }

    @Test
    void softDeleteFilteredBySqlRestriction() {
        var order = persistOrder(alice, OrderStatus.PENDING);
        order.markDeleted("alice");
        em.persistAndFlush(order);
        em.clear();

        var result = repo.findByUserIdOrderByCreatedAtDesc(alice, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    private Order persistOrder(UUID userId, OrderStatus status) {
        Order order = Order.builder().userId(userId).status(status)
            .subtotal(BigDecimal.TEN).taxAmount(BigDecimal.ZERO).discountAmount(BigDecimal.ZERO)
            .total(BigDecimal.TEN).build();
        return em.persistAndFlush(order);
    }
}