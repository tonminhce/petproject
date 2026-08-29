package com.shop.inventoryservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.inventoryservice.config.TestLiquibaseConfig;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors product-service's / favourite-service's {@code @DataJpaTest} slice:
 * Testcontainers Postgres + real Liquibase schema, covering the repository
 * behaviours the reservation lifecycle depends on — {@code @Version} optimistic
 * locking, the expired-reservation sweep query, retention bulk deletes, and the
 * active-reservation count that guards {@code delete()}.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingAutoConfiguration.class,
    LiquibaseAutoConfiguration.class,
    TestLiquibaseConfig.class
})
class InventoryRepositoryTest {

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
        // Liquibase owns the schema; Hibernate must NOT validate before Liquibase runs.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private TestEntityManager em;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Inventory persistInventory(UUID productId, int available) {
        Inventory inv = Inventory.builder()
            .productId(productId)
            .availableQuantity(available)
            .reservedQuantity(0)
            .build();
        return em.persistAndFlush(inv);
    }

    private Reservation persistReservation(UUID productId, ReservationStatus status,
                                           Instant createdAt, Instant expiresAt) {
        Reservation r = Reservation.builder()
            .productId(productId)
            .quantity(5)
            .status(status)
            .createdAt(createdAt)
            .expiresAt(expiresAt)
            .build();
        return em.persistAndFlush(r);
    }

    private OutboxEvent persistOutbox(OutboxStatus status, Instant sentAt) {
        OutboxEvent e = OutboxEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .aggregateType("Inventory")
            .aggregateId(UUID.randomUUID())
            .eventType("inventory.adjusted.v1")
            .topic("shop.inventory.events.v1")
            .payload("{}")
            .status(status)
            .retryCount(0)
            .sentAt(sentAt)
            .build();
        return em.persistAndFlush(e);
    }

    @Test
    void findByProductId_returnsRow_andExistsReflectsIt() {
        UUID productId = UUID.randomUUID();
        persistInventory(productId, 100);

        assertThat(inventoryRepository.findByProductId(productId)).isPresent();
        assertThat(inventoryRepository.findByProductId(UUID.randomUUID())).isEmpty();
        assertThat(inventoryRepository.existsByProductId(productId)).isTrue();
        assertThat(inventoryRepository.existsByProductId(UUID.randomUUID())).isFalse();
    }

    @Test
    void versionIncrementsOnUpdate_provingOptimisticLockIsWired() {
        UUID productId = UUID.randomUUID();
        Inventory saved = persistInventory(productId, 100);
        UUID id = saved.getId();
        em.clear();

        Inventory managed = em.find(Inventory.class, id);
        assertThat(managed.getVersion()).isEqualTo(0L);
        managed.setAvailableQuantity(90);
        em.flush();
        em.clear();

        assertThat(em.find(Inventory.class, id).getVersion()).isEqualTo(1L);
    }

    @Test
    void staleWrite_throwsOptimisticLockingFailure() {
        UUID productId = UUID.randomUUID();
        Inventory saved = persistInventory(productId, 100);
        UUID id = saved.getId();
        em.clear();

        // Current writer bumps the row to version 1…
        Inventory current = em.find(Inventory.class, id);
        current.setAvailableQuantity(90);
        em.flush();
        em.clear();

        // …while a stale writer still holds version 0. The stale write goes
        // through the REPOSITORY (not the raw EntityManager) so Spring Data's
        // exception translation applies — exactly like production.
        Inventory stale = em.find(Inventory.class, id);
        em.detach(stale);
        stale.setVersion(0L);
        stale.setAvailableQuantity(99);

        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(stale))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void expiredSweepQuery_matchesOnlyPendingAndExpired() {
        UUID productId = UUID.randomUUID();
        Instant now = Instant.now();
        persistReservation(productId, ReservationStatus.PENDING, now.minusSeconds(1800), now.minusSeconds(60));   // match
        persistReservation(productId, ReservationStatus.PENDING, now.minusSeconds(60), now.plusSeconds(900));      // not expired
        persistReservation(productId, ReservationStatus.COMMITTED, now.minusSeconds(1800), now.minusSeconds(60));  // wrong status

        List<Reservation> matched = reservationRepository.findByStatusAndExpiresAtBefore(
            ReservationStatus.PENDING, now, PageRequest.of(0, 50));

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(matched.get(0).getExpiresAt()).isBefore(now);
    }

    @Test
    void countByProductIdAndStatusIn_countsOnlyRequestedStatuses() {
        UUID productId = UUID.randomUUID();
        Instant now = Instant.now();
        persistReservation(productId, ReservationStatus.PENDING, now, now.plusSeconds(900));
        persistReservation(productId, ReservationStatus.COMMITTED, now, now.plusSeconds(900));
        persistReservation(productId, ReservationStatus.RELEASED, now, now.plusSeconds(900));

        long active = reservationRepository.countByProductIdAndStatusIn(
            productId, List.of(ReservationStatus.PENDING, ReservationStatus.COMMITTED));

        assertThat(active).isEqualTo(2L);
    }

    @Test
    void reservationRetention_deletesOnlyExpiredBeforeCutoff() {
        UUID productId = UUID.randomUUID();
        Instant now = Instant.now();
        persistReservation(productId, ReservationStatus.EXPIRED, now.minusSeconds(31L * 24 * 3600), now.minusSeconds(30L * 24 * 3600)); // purge
        persistReservation(productId, ReservationStatus.EXPIRED, now, now.minusSeconds(60));                                          // too recent
        persistReservation(productId, ReservationStatus.PENDING, now.minusSeconds(31L * 24 * 3600), now.minusSeconds(60));            // wrong status

        int deleted = reservationRepository.deleteByStatusAndCreatedAtBefore(
            ReservationStatus.EXPIRED, now.minusSeconds(30L * 24 * 3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(reservationRepository.count()).isEqualTo(2);
    }

    @Test
    void outboxRetention_deletesOnlySentBeforeCutoff() {
        Instant now = Instant.now();
        persistOutbox(OutboxStatus.SENT, now.minusSeconds(8L * 24 * 3600));    // purge
        persistOutbox(OutboxStatus.SENT, now.minusSeconds(3600));              // too recent
        persistOutbox(OutboxStatus.PENDING, now.minusSeconds(8L * 24 * 3600)); // wrong status

        int deleted = outboxEventRepository.deleteByStatusAndSentAtBefore(
            OutboxStatus.SENT, now.minusSeconds(7L * 24 * 3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }
}
