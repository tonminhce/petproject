package com.shop.inventoryservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.inventoryservice.config.TestLiquibaseConfig;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingAutoConfiguration.class, LiquibaseAutoConfiguration.class, TestLiquibaseConfig.class})
class ReservationsOrderIdIndexIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("inventory_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    ReservationRepository reservationRepository;

    @Test
    @DisplayName("the index on reservations(order_id) exists")
    void theIndexOnOrderIdExists() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'reservations' AND indexname = 'idx_reservations_order_id'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("a by-order-id lookup uses the index instead of a sequential scan")
    void aByOrderIdLookupUsesTheIndex() {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        inventoryRepository.save(Inventory.builder()
                .productId(productId)
                .availableQuantity(10)
                .reservedQuantity(0)
                .build());
        reservationRepository.save(Reservation.builder()
                .productId(productId)
                .quantity(1)
                .status(ReservationStatus.PENDING)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .orderId(orderId)
                .build());

        List<Map<String, Object>> plan = jdbc.queryForList("""
                EXPLAIN (COSTS OFF) SELECT * FROM reservations WHERE order_id = ?
                """, orderId);

        assertThat(plan.toString())
                .as("the by-order-id lookup must be served by idx_reservations_order_id")
                .contains("idx_reservations_order_id");
    }

    @Test
    @DisplayName("the inventory row stays deletable while only released reservations remain")
    void theInventoryRowStaysDeletableWithReleasedReservations() {

        UUID productId = UUID.randomUUID();
        inventoryRepository.save(Inventory.builder()
                .productId(productId)
                .availableQuantity(10)
                .reservedQuantity(0)
                .build());
        reservationRepository.save(Reservation.builder()
                .productId(productId)
                .quantity(1)
                .status(ReservationStatus.RELEASED)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build());

        UUID inventoryId = inventoryRepository.findByProductId(productId).orElseThrow().getId();
        inventoryRepository.deleteById(inventoryId);
        inventoryRepository.flush();

        assertThat(inventoryRepository.findByProductId(productId)).isEmpty();
    }
}
