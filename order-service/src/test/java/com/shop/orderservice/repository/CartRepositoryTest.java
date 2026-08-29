package com.shop.orderservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.entity.Cart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingAutoConfiguration.class, LiquibaseAutoConfiguration.class, TestLiquibaseConfig.class})
class CartRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("cart_repo_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TestEntityManager em;
    @Autowired private CartRepository cartRepository;

    private final UUID alice = UUID.randomUUID();

    @Test
    void uniqueUserIdConstraint_blocksDuplicate() {
        Cart cart1 = Cart.builder().userId(alice).subtotal(BigDecimal.ZERO).build();
        em.persistAndFlush(cart1);

        Cart cart2 = Cart.builder().userId(alice).subtotal(BigDecimal.ZERO).build();
        assertThatThrownBy(() -> cartRepository.saveAndFlush(cart2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByUserIdAndDeletedFalse_excludesSoftDeleted() {
        Cart cart = Cart.builder().userId(alice).subtotal(BigDecimal.ZERO).build();
        em.persistAndFlush(cart);
        cart.markDeleted("alice");
        em.persistAndFlush(cart);
        em.clear();

        assertThat(cartRepository.findByUserIdAndDeletedFalse(alice)).isEmpty();
    }
}