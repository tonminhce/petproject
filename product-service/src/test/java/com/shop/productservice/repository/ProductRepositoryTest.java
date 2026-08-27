package com.shop.productservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.entity.Category;
import com.shop.productservice.entity.Product;
import com.shop.productservice.entity.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.productservice.config.TestLiquibaseConfig;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingAutoConfiguration.class,
    LiquibaseAutoConfiguration.class,
    TestLiquibaseConfig.class
})
class ProductRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("product_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        // Liquibase owns the schema; Hibernate must NOT validate before Liquibase runs.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired TestEntityManager em;
    @Autowired ProductRepository productRepository;

    private Category category;
    private Brand brand;

    @BeforeEach
    void setUp() {
        category = Category.builder().title("Phones").slug("phones").build();
        em.persistAndFlush(category);
        brand = Brand.builder().name("Acme").slug("acme").build();
        em.persistAndFlush(brand);
    }

    @Test
    void findWithRelationsById_returnsProductWithCategoryAndBrand() {
        Product p = Product.builder()
            .title("iPhone 15").slug("iphone-15").sku("IP15-001")
            .priceUnit(new BigDecimal("999.00")).quantity(10)
            .status(ProductStatus.ACTIVE).category(category).brand(brand)
            .build();
        em.persistAndFlush(p);
        em.clear();

        Optional<Product> result = productRepository.findWithRelationsById(p.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getCategory().getTitle()).isEqualTo("Phones");
        assertThat(result.get().getBrand().getName()).isEqualTo("Acme");
    }

    @Test
    void findWithRelationsBySlug_excludesSoftDeleted() {
        Product active = Product.builder()
            .title("Active").sku("A-1")
            .priceUnit(BigDecimal.ONE).quantity(1)
            .status(ProductStatus.ACTIVE).slug("active").build();
        Product deleted = Product.builder()
            .title("Deleted").sku("D-1")
            .priceUnit(BigDecimal.ONE).quantity(1)
            .status(ProductStatus.DISCONTINUED).slug("deleted").build();
        deleted.markDeleted("test");
        em.persistAndFlush(active);
        em.persistAndFlush(deleted);
        em.clear();

        assertThat(productRepository.findWithRelationsBySlug("active")).isPresent();
        assertThat(productRepository.findWithRelationsBySlug("deleted")).isEmpty();
    }

    @Test
    void findAllWithFilterByCategoryAndStatus() {
        Product p1 = Product.builder().title("P1").slug("p1").sku("P1").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.ACTIVE).category(category).build();
        Product p2 = Product.builder().title("P2").slug("p2").sku("P2").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.DRAFT).category(category).build();
        em.persistAndFlush(p1);
        em.persistAndFlush(p2);

        Specification<Product> spec = (root, query, cb) ->
            cb.and(
                cb.equal(root.get("category").get("id"), category.getId()),
                cb.equal(root.get("status"), ProductStatus.ACTIVE)
            );

        Page<Product> page = productRepository.findAll(spec, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("P1");
    }

    @Test
    void existsBySlugAndIdNot_worksForUpdate() {
        Product p = Product.builder().title("T").slug("t").sku("T").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.ACTIVE).build();
        em.persistAndFlush(p);

        assertThat(productRepository.existsBySlugAndIdNot("t", UUID.randomUUID())).isTrue();
        assertThat(productRepository.existsBySlugAndIdNot("t", p.getId())).isFalse();
        assertThat(productRepository.existsBySlugAndIdNot("other", p.getId())).isFalse();
    }
}