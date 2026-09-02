package com.shop.promotionservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import com.shop.promotionservice.service.CampaignReservationService;
import com.shop.promotionservice.service.DiscountCalculator;
import com.shop.promotionservice.service.PromotionEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignReservationServiceImpl implements CampaignReservationService {

    /**
     * Usages that hold quota. Spec §5.1 says "PENDING, CONFIRMED" — CONFIRMED
     * is {@code COMMITTED} in the landed {@link UsageStatus} enum.
     */
    private static final List<UsageStatus> COUNTED_STATUSES =
        List.of(UsageStatus.PENDING, UsageStatus.COMMITTED);

    private final CampaignRepository campaignRepository;
    private final CouponUsageReservationRepository reservationRepository;
    private final PromotionEventPublisher eventPublisher;
    private final Clock clock;

    @Value("${shop.promotion.reservation-ttl-seconds:900}")
    private long reservationTtlSeconds;

    /**
     * Reserve flow (spec §5.1). Gate order is load-bearing:
     * NOT_FOUND → NOT_ACTIVE → MIN_ORDER → PER_USER → MAX_REDEMPTIONS → BUDGET.
     * Campaign lookup never needs a deleted-predicate — {@code @SQLRestriction}
     * on {@link Campaign} hides soft-deleted rows from every query.
     */
    @Override
    @Transactional
    public ReservationResponse reserve(String code, ReserveRequest request) {
        Campaign campaign = campaignRepository.findByCode(code)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CAMPAIGN_NOT_FOUND, code));
        Instant now = clock.instant();

        if (campaign.getStatus() != CampaignStatus.ACTIVE
                || (campaign.getStartsAt() != null && now.isBefore(campaign.getStartsAt()))
                || (campaign.getEndsAt() != null && now.isAfter(campaign.getEndsAt()))) {
            throw BusinessException.of(ErrorCode.CAMPAIGN_NOT_ACTIVE, code);
        }
        if (campaign.getMinOrderAmount() != null
                && request.orderAmount().compareTo(campaign.getMinOrderAmount()) < 0) {
            throw BusinessException.of(ErrorCode.MIN_ORDER_AMOUNT_NOT_MET, request.orderAmount());
        }

        CouponUsageReservation existing = reservationRepository.findByOrderId(request.orderId()).orElse(null);
        if (existing != null) {
            if (!existing.getCampaignId().equals(campaign.getId())
                    || !existing.getUserId().equals(request.userId())
                    || existing.getOrderAmount().compareTo(request.orderAmount()) != 0) {
                throw BusinessException.of(ErrorCode.PROMOTION_DUPLICATE_REQUEST, request.orderId());
            }
            return ReservationResponse.from(campaign, existing);
        }

        // Frozen up-front (D3): budget check needs the new discount, and the
        // returned amounts must match the stored row exactly.
        BigDecimal discount = DiscountCalculator.compute(
            campaign.getDiscountType(), campaign.getDiscountValue(), request.orderAmount());

        if (campaign.getPerUserLimit() > 0
                && reservationRepository.countByCampaignIdAndUserIdAndStatusIn(
                    campaign.getId(), request.userId(), COUNTED_STATUSES) >= campaign.getPerUserLimit()) {
            throw BusinessException.of(ErrorCode.PER_USER_LIMIT_EXCEEDED, request.userId());
        }
        if (campaign.getMaxRedemptions() != null
                && reservationRepository.countByCampaignIdAndStatusIn(
                    campaign.getId(), COUNTED_STATUSES) >= campaign.getMaxRedemptions()) {
            throw BusinessException.of(ErrorCode.BUDGET_EXHAUSTED, campaign.getCode());
        }
        if (campaign.getTotalBudget() != null
                && reservationRepository.sumDiscountByCampaignIdAndStatusIn(
                    campaign.getId(), COUNTED_STATUSES).add(discount)
                        .compareTo(campaign.getTotalBudget()) > 0) {
            throw BusinessException.of(ErrorCode.BUDGET_EXHAUSTED, campaign.getCode());
        }

        // ⚠️ Race protection (spec §5.2) — explicit choice: version-touch on the campaign row.
        // Checks 2c-2e are check-then-insert against the reservation table; under READ_COMMITTED
        // two concurrent reserves can both pass. Touching a mapped field + saveAndFlush makes
        // THIS tx issue an UPDATE on campaign → the flush compares @Version: the loser gets
        // OptimisticLockingFailureException → the retry wrapper re-reads (now seeing the
        // winner's committed PENDING row) and re-validates → correctly rejected.
        campaign.setUpdatedAt(now);
        campaignRepository.saveAndFlush(campaign);

        CouponUsageReservation reservation = CouponUsageReservation.builder()
            .campaignId(campaign.getId())
            .userId(request.userId())
            .orderId(request.orderId())
            .orderAmount(request.orderAmount())
            .discountAmount(discount)
            .status(UsageStatus.PENDING)
            .expiresAt(now.plusSeconds(reservationTtlSeconds))
            .reservedAt(now)
            .build();
        CouponUsageReservation saved = reservationRepository.save(reservation);
        eventPublisher.publishReserved(campaign, saved);
        return ReservationResponse.from(campaign, saved);
    }

    /**
     * Idempotent state machine (spec §5.3, branch order load-bearing):
     * COMMITTED → retry OK; RELEASED/EXPIRED → PRO-7010 fail-closed (caller
     * view diverged — reconciliation owns it); expired pending → PRO-7009;
     * PENDING → COMMITTED + committedAt + {@code promotion.committed.v1}.
     */
    @Override
    @Transactional
    public void commit(UUID id) {
        CouponUsageReservation reservation = loadReservation(id);
        Instant now = clock.instant();
        if (reservation.getStatus() == UsageStatus.COMMITTED) {
            log.info("Reservation {} already committed (idempotent retry)", id);
            return;
        }
        if (reservation.getStatus() == UsageStatus.RELEASED
                || reservation.getStatus() == UsageStatus.EXPIRED) {
            throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_INVALID_STATE, id);
        }
        if (!reservation.getExpiresAt().isAfter(now)) {
            throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_EXPIRED, id);
        }
        Campaign campaign = loadCampaign(reservation);
        reservation.setStatus(UsageStatus.COMMITTED);
        reservation.setCommittedAt(now);
        reservationRepository.save(reservation);
        eventPublisher.publishCommitted(campaign, reservation);
    }

    /**
     * PENDING → RELEASED + releasedAt + {@code promotion.released.v1}
     * ({@code previousStatus="PENDING"}). Terminal states are idempotent
     * no-ops (quota already returned); COMMITTED → PRO-7010 — the
     * commit→rollback path is {@link #releaseCommitted(UUID)} (§5.3).
     */
    @Override
    @Transactional
    public void release(UUID id) {
        CouponUsageReservation reservation = loadReservation(id);
        if (reservation.getStatus() == UsageStatus.RELEASED
                || reservation.getStatus() == UsageStatus.EXPIRED) {
            log.info("Reservation {} already terminalized (idempotent retry)", id);
            return;
        }
        if (reservation.getStatus() != UsageStatus.PENDING) {
            throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_INVALID_STATE, id);
        }
        Campaign campaign = loadCampaign(reservation);
        reservation.setStatus(UsageStatus.RELEASED);
        reservation.setReleasedAt(clock.instant());
        reservationRepository.save(reservation);
        eventPublisher.publishReleased(campaign, reservation, UsageStatus.PENDING.name());
    }

    /**
     * Half-commit rollback (spec §5.3): COMMITTED → RELEASED with
     * {@code previousStatus="COMMITTED"} so consumers distinguish
     * confirm-flow compensation from a plain reserve→release. Terminal
     * states are idempotent no-ops; PENDING belongs to {@link #release(UUID)}.
     */
    @Override
    @Transactional
    public void releaseCommitted(UUID id) {
        CouponUsageReservation reservation = loadReservation(id);
        if (reservation.getStatus() == UsageStatus.RELEASED
                || reservation.getStatus() == UsageStatus.EXPIRED) {
            log.info("Reservation {} already terminalized (idempotent retry)", id);
            return;
        }
        if (reservation.getStatus() != UsageStatus.COMMITTED) {
            throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_INVALID_STATE, id);
        }
        Campaign campaign = loadCampaign(reservation);
        reservation.setStatus(UsageStatus.RELEASED);
        reservation.setReleasedAt(clock.instant());
        reservationRepository.save(reservation);
        eventPublisher.publishReleased(campaign, reservation, UsageStatus.COMMITTED.name());
    }

    /** Read-only projection for reconciliation polling (spec §5.3). */
    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getState(UUID id) {
        CouponUsageReservation reservation = loadReservation(id);
        Campaign campaign = loadCampaign(reservation);
        return ReservationResponse.from(campaign, reservation);
    }

    private CouponUsageReservation loadReservation(UUID id) {
        return reservationRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PROMOTION_RESERVATION_NOT_FOUND, id));
    }

    private Campaign loadCampaign(CouponUsageReservation reservation) {
        return campaignRepository.findById(reservation.getCampaignId())
            .orElseThrow(() -> BusinessException.of(
                ErrorCode.CAMPAIGN_NOT_FOUND, reservation.getCampaignId()));
    }
}
