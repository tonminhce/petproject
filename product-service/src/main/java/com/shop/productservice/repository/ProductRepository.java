package com.shop.productservice.repository;

import com.shop.productservice.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository
        extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsById(UUID id);

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsBySlug(String slug);

    /** Live products pointing at the given media — the MediaDeleted clear set (spec D4). */
    @EntityGraph(attributePaths = {"category", "brand"})
    List<Product> findByMediaId(UUID mediaId);

    /**
     * H-3 reconciliation sweep: one bounded page of rows holding ANY media
     * reference ({@code media_id IS NOT NULL LIMIT n}). Served by the
     * {@code idx_products_media_id} index (changelog 005) — same structure the
     * MediaDeleted clear-set query uses.
     */
    Page<Product> findByMediaIdIsNotNull(Pageable pageable);

    /**
     * Executor override with category+brand fetch-joined (backoffice detail
     * list, F2). Scalar ManyToOne joins only — pagination stays in SQL.
     */
    @Override
    @EntityGraph(attributePaths = {"category", "brand"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    boolean existsBySlug(String slug);

    boolean existsBySku(String sku);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    boolean existsBySkuAndIdNot(String sku, UUID id);
}