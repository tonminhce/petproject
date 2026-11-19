package com.shop.favouriteservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.favouriteservice.config.TestLiquibaseConfig;
import com.shop.favouriteservice.entity.Favourite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors product-service's {@code ProductRepositoryTest} pattern exactly so the
 * fleet stays consistent: {@code @DataJpaTest} with {@code Replace.NONE}, explicit
 * imports for {@code JpaAuditingAutoConfiguration} + {@code LiquibaseAutoConfiguration}
 * + {@code TestLiquibaseConfig}, a static {@code @Container} + {@code @DynamicPropertySource}
 * (NOT {@code @ServiceConnection}).
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingAutoConfiguration.class,
    LiquibaseAutoConfiguration.class,
    TestLiquibaseConfig.class
})
class FavouriteRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("favourite_test")
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
    private FavouriteRepository repo;

    private static final UUID alice = UUID.randomUUID();

    private Favourite persistFavourite(UUID userId, UUID productId) {
        Favourite fav = Favourite.builder().userId(userId).productId(productId).build();
        return em.persistAndFlush(fav);
    }

    @Test
    void findByUserId_filtersSoftDeleted() {
        Favourite active = persistFavourite(alice, UUID.randomUUID());
        Favourite tombstoned = persistFavourite(alice, UUID.randomUUID());
        em.getEntityManager()
                .createQuery("UPDATE Favourite f SET f.deleted = true, f.deletedAt = CURRENT_TIMESTAMP WHERE f.id = :id")
                .setParameter("id", tombstoned.getId())
                .executeUpdate();

        Pageable page = PageRequest.of(0, 20);
        List<Favourite> result = repo.findByUserIdOrderByCreatedAtDesc(alice, page).getContent();

        assertThat(result).extracting(Favourite::getId).containsExactly(active.getId());
    }

    @Test
    void softDeleteByUserIdAndProductId_keepsRowAndSetsFlags() {
        Favourite fav = persistFavourite(alice, UUID.randomUUID());
        UUID productId = fav.getProductId();

        int affected = repo.softDeleteByUserIdAndProductId(alice, productId, "alice");

        assertThat(affected).isEqualTo(1);
        em.clear();
        // Native query bypasses @SQLRestriction so we can read the tombstoned row.
        Favourite raw = (Favourite) em.getEntityManager()
                .createNativeQuery("SELECT * FROM favourites WHERE id = ?1", Favourite.class)
                .setParameter(1, fav.getId())
                .getSingleResult();
        assertThat(raw.isDeleted()).isTrue();
        assertThat(raw.getDeletedAt()).isNotNull();
        assertThat(raw.getDeletedBy()).isEqualTo("alice");
        // Repository finder still skips soft-deleted rows.
        assertThat(repo.findByUserIdOrderByCreatedAtDesc(alice, PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void partialUniqueConstraint_allowsReAddingAfterSoftDelete() {
        UUID productId = UUID.randomUUID();
        persistFavourite(alice, productId);
        int deleted = repo.softDeleteByUserIdAndProductId(alice, productId, "alice");
        assertThat(deleted).isEqualTo(1);
        em.clear();

        // After soft-delete, the partial unique index releases (user, product), so the
        // same pair can be re-inserted.
        Favourite re = persistFavourite(alice, productId);
        assertThat(re.getId()).isNotNull();
    }
}
