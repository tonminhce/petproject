package com.shop.promotionservice.repository;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Coupon usage reservation persistence. Hard-delete semantics (no soft-delete
 * columns), so every derived query targets rows directly.
 */
public interface CouponUsageReservationRepository extends JpaRepository<CouponUsageReservation, UUID> {

    long countByCampaignIdAndUserIdAndStatusIn(UUID campaignId, UUID userId, Collection<UsageStatus> statuses);

    long countByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);

    @Query("select coalesce(sum(r.discountAmount), 0) from CouponUsageReservation r " +
           "where r.campaignId = :campaignId and r.status in :statuses")
    BigDecimal sumDiscountByCampaignIdAndStatusIn(@Param("campaignId") UUID campaignId,
                                                  @Param("statuses") Collection<UsageStatus> statuses);

    boolean existsByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);

    Page<CouponUsageReservation> findByCampaignId(UUID campaignId, Pageable pageable);

    Optional<CouponUsageReservation> findByOrderId(UUID orderId);

    List<CouponUsageReservation> findByStatusAndExpiresAtBefore(UsageStatus status, Instant now, Pageable pageable);

    List<CouponUsageReservation> findByStatusInAndReleasedAtBefore(Collection<UsageStatus> statuses, Instant cutoff, Pageable pageable);

    /**
     * Retention purge candidates (T11): terminal rows whose effective end instant
     * — max of committedAt, releasedAt, fallback reservedAt for non-released rows —
     * is older than the cutoff.
     */
    @Query("select r from CouponUsageReservation r where r.status in :statuses and " +
           "coalesce(r.committedAt, r.releasedAt, r.reservedAt) < :cutoff")
    List<CouponUsageReservation> findTerminalBefore(@Param("statuses") Collection<UsageStatus> statuses,
                                                    @Param("cutoff") Instant cutoff, Pageable pageable);
}
