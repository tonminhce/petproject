package com.shop.promotionservice.repository;

import com.shop.promotionservice.entity.CampaignUserUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CampaignUserUsageRepository extends JpaRepository<CampaignUserUsage, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO campaign_user_usage (campaign_id, user_id, used_count) "
                 + "VALUES (:campaignId, :userId, 1) "
                 + "ON CONFLICT (campaign_id, user_id) DO UPDATE "
                 + "SET used_count = campaign_user_usage.used_count + 1 "
                 + "WHERE campaign_user_usage.used_count < :limit", nativeQuery = true)
    int claimQuota(@Param("campaignId") UUID campaignId,
                   @Param("userId") UUID userId,
                   @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE campaign_user_usage SET used_count = used_count - 1 "
                 + "WHERE campaign_id = :campaignId AND user_id = :userId AND used_count > 0", nativeQuery = true)
    int releaseQuota(@Param("campaignId") UUID campaignId, @Param("userId") UUID userId);
}
