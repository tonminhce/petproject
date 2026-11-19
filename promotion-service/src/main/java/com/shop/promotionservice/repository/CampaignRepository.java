package com.shop.promotionservice.repository;

import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.entity.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    Optional<Campaign> findByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    boolean existsByCode(String code);

    Page<Campaign> findAllByStatus(CampaignStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE campaign SET used_count = used_count + 1, budget_used = budget_used + :discount "
                 + "WHERE id = :id AND deleted = false "
                 + "AND (max_redemptions IS NULL OR used_count < max_redemptions) "
                 + "AND (total_budget IS NULL OR budget_used + :discount <= total_budget)", nativeQuery = true)
    int claimQuota(@Param("id") UUID id, @Param("discount") BigDecimal discount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE campaign SET used_count = greatest(used_count - 1, 0), "
                 + "budget_used = greatest(budget_used - :discount, 0) "
                 + "WHERE id = :id AND deleted = false", nativeQuery = true)
    int releaseQuota(@Param("id") UUID id, @Param("discount") BigDecimal discount);
}
