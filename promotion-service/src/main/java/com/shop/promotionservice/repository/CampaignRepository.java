package com.shop.promotionservice.repository;

import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.entity.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Campaign persistence.
 *
 * <p>{@code Campaign} carries {@code @SQLRestriction("deleted = false")}, so Hibernate
 * excludes soft-deleted rows from every query (derived, JPQL, and {@code findAll}) —
 * derived method names therefore need no explicit {@code DeletedFalse} predicate.
 * Paged listing without filters is the inherited {@link JpaRepository#findAll(Pageable)}.
 */
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    Optional<Campaign> findByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    Page<Campaign> findAllByStatus(CampaignStatus status, Pageable pageable);
}
