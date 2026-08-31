package com.shop.orderservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
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

/**
 * Task 7 — rating eligibility probe: only items of DELIVERED, non-deleted orders
 * belonging to the requesting user are returned for a given product.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingAutoConfiguration.class, LiquibaseAutoConfiguration.class, TestLiquibaseConfig.class})
class OrderItemRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_item_repo_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TestEntityManager em;
    @Autowired private OrderItemRepository repo;

    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-000000008001");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000008002");

    @Test
    void findDeliveredByUserAndProduct_returnsDeliveredItemsOnly() {
        OrderItem item = persistOrderWithItem(alice, OrderStatus.DELIVERED);

        var result = repo.findDeliveredByUserAndProduct(
            alice, productId, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OrderItem::getId)
            .containsExactly(item.getId());
    }

    @Test
    void findDeliveredByUserAndProduct_excludesShippedAndPending() {
        persistOrderWithItem(alice, OrderStatus.SHIPPED);
        persistOrderWithItem(alice, OrderStatus.PENDING);

        var result = repo.findDeliveredByUserAndProduct(
            alice, productId, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findDeliveredByUserAndProduct_excludesOtherUsers() {
        persistOrderWithItem(UUID.randomUUID(), OrderStatus.DELIVERED);

        var result = repo.findDeliveredByUserAndProduct(
            alice, productId, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findDeliveredByUserAndProduct_excludesSoftDeletedOrders() {
        OrderItem item = persistOrderWithItem(alice, OrderStatus.DELIVERED);
        Order order = em.find(Order.class, item.getOrderId());
        order.markDeleted(alice.toString());
        em.persistAndFlush(order);
        em.clear();

        var result = repo.findDeliveredByUserAndProduct(
            alice, productId, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findDeliveredByUserAndProduct_paginates() {
        persistOrderWithItem(alice, OrderStatus.DELIVERED);
        persistOrderWithItem(alice, OrderStatus.DELIVERED);

        var firstPage = repo.findDeliveredByUserAndProduct(
            alice, productId, PageRequest.of(0, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2L);
        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    private OrderItem persistOrderWithItem(UUID userId, OrderStatus status) {
        Order order = Order.builder().userId(userId).status(status)
            .subtotal(BigDecimal.TEN).taxAmount(BigDecimal.ZERO).discountAmount(BigDecimal.ZERO)
            .total(BigDecimal.TEN).build();
        order = em.persistAndFlush(order);

        OrderItem item = OrderItem.builder()
            .orderId(order.getId())
            .productId(productId)
            .productTitle("Widget")
            .quantity(1)
            .unitPrice(BigDecimal.TEN)
            .lineTotal(BigDecimal.TEN)
            .build();
        return em.persistAndFlush(item);
    }
}
