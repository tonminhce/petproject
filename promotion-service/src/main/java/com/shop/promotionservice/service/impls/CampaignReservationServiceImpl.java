package com.shop.promotionservice.service.impls;

import com.shop.common.core.constants.PlatformCurrency;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.PromotionErrorCode;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CampaignUserUsageRepository;
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
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignReservationServiceImpl implements CampaignReservationService {

    private final CampaignRepository campaignRepository;
    private final CouponUsageReservationRepository reservationRepository;
    private final CampaignUserUsageRepository campaignUserUsageRepository;
    private final PromotionEventPublisher eventPublisher;
    private final Clock clock;

    @Value("${shop.promotion.reservation-ttl-seconds:900}")
    private long reservationTtlSeconds;

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
        String currency = resolveCurrency(request.currency());
        if (!currency.equals(campaign.getCurrency())) {
            throw BusinessException.of(PromotionErrorCode.PROMOTION_CURRENCY_MISMATCH, currency, campaign.getCurrency());
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
            if (existing.getStatus() != UsageStatus.PENDING) {
                throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_INVALID_STATE, existing.getStatus());
            }
            return ReservationResponse.from(campaign, existing);
        }

        BigDecimal discount = DiscountCalculator.compute(
            campaign.getDiscountType(), campaign.getDiscountValue(), request.orderAmount());

        if (campaign.getPerUserLimit() > 0
                && campaignUserUsageRepository.claimQuota(
                    campaign.getId(), request.userId(), campaign.getPerUserLimit()) == 0) {
            throw BusinessException.of(ErrorCode.PER_USER_LIMIT_EXCEEDED, request.userId());
        }
        if (campaignRepository.claimQuota(campaign.getId(), discount) == 0) {
            throw BusinessException.of(ErrorCode.BUDGET_EXHAUSTED, campaign.getCode());
        }

        CouponUsageReservation reservation = CouponUsageReservation.builder()
            .campaignId(campaign.getId())
            .userId(request.userId())
            .orderId(request.orderId())
            .orderAmount(request.orderAmount())
            .discountAmount(discount)
            .currency(currency)
            .status(UsageStatus.PENDING)
            .expiresAt(now.plusSeconds(reservationTtlSeconds))
            .reservedAt(now)
            .build();
        CouponUsageReservation saved;
        try {
            saved = reservationRepository.save(reservation);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {

            log.info("Concurrent coupon reservation for order {} rejected by uk_cur_order_id",
                    request.orderId());
            throw BusinessException.of(ErrorCode.PROMOTION_DUPLICATE_REQUEST, request.orderId());
        }
        eventPublisher.publishReserved(campaign, saved);
        return ReservationResponse.from(campaign, saved);
    }

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
        releaseQuota(campaign.getId(), reservation);
        eventPublisher.publishReleased(campaign, reservation, UsageStatus.PENDING.name());
    }

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
        releaseQuota(campaign.getId(), reservation);
        eventPublisher.publishReleased(campaign, reservation, UsageStatus.COMMITTED.name());
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getState(UUID id) {
        CouponUsageReservation reservation = loadReservation(id);
        Campaign campaign = loadCampaign(reservation);
        return ReservationResponse.from(campaign, reservation);
    }

    private String resolveCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return PlatformCurrency.DEFAULT;
        }
        return PlatformCurrency.normalize(currency);
    }

    private CouponUsageReservation loadReservation(UUID id) {
        return reservationRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PROMOTION_RESERVATION_NOT_FOUND, id));
    }

    private void releaseQuota(UUID campaignId, CouponUsageReservation reservation) {
        campaignRepository.releaseQuota(campaignId, reservation.getDiscountAmount());
        campaignUserUsageRepository.releaseQuota(campaignId, reservation.getUserId());
    }

    private Campaign loadCampaign(CouponUsageReservation reservation) {
        return campaignRepository.findById(reservation.getCampaignId())
            .orElseThrow(() -> BusinessException.of(
                ErrorCode.CAMPAIGN_NOT_FOUND, reservation.getCampaignId()));
    }
}
