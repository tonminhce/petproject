package com.shop.promotionservice.service.impls;

import com.shop.common.core.audit.AuditActor;
import com.shop.common.core.constants.PlatformCurrency;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.PromotionErrorCode;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.CampaignRequest;
import com.shop.promotionservice.dto.response.CampaignResponse;
import com.shop.promotionservice.dto.response.CampaignUsageResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import com.shop.promotionservice.service.CampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private static final List<UsageStatus> COUNTED_STATUSES =
        List.of(UsageStatus.PENDING, UsageStatus.COMMITTED);

    private final CampaignRepository campaignRepository;
    private final CouponUsageReservationRepository reservationRepository;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public Page<CampaignResponse> findAll(CampaignStatus status, Pageable pageable) {
        Page<Campaign> page = status == null
            ? campaignRepository.findAll(pageable)
            : campaignRepository.findAllByStatus(status, pageable);
        return page.map(CampaignResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignResponse findById(UUID id) {
        return campaignRepository.findById(id)
            .map(CampaignResponse::from)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CAMPAIGN_NOT_FOUND, id));
    }

    @Override
    @Transactional
    public CampaignResponse create(CampaignRequest request) {
        if (campaignRepository.existsByCode(request.code())) {
            throw BusinessException.of(ErrorCode.CAMPAIGN_ALREADY_EXISTS, request.code());
        }
        Campaign campaign = Campaign.builder()
            .code(request.code())
            .name(request.name())
            .discountType(request.discountType())
            .discountValue(request.discountValue())
            .minOrderAmount(request.minOrderAmount())
            .startsAt(request.startsAt())
            .endsAt(request.endsAt())
            .maxRedemptions(request.maxRedemptions())
            .totalBudget(request.totalBudget())
            .currency(resolveCurrency(request.currency()))
            .perUserLimit(request.perUserLimit() != null ? request.perUserLimit() : 1)
            .status(request.status() != null ? request.status() : CampaignStatus.INACTIVE)
            .build();
        return CampaignResponse.from(campaignRepository.save(campaign));
    }

    @Override
    @Transactional
    public CampaignResponse update(UUID id, CampaignRequest request) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CAMPAIGN_NOT_FOUND, id));
        if (!request.code().equals(campaign.getCode())
                && campaignRepository.existsByCodeAndIdNot(request.code(), id)) {
            throw BusinessException.of(ErrorCode.CAMPAIGN_ALREADY_EXISTS, request.code());
        }

        campaign.setCode(request.code());
        campaign.setName(request.name());
        campaign.setDiscountType(request.discountType());
        campaign.setDiscountValue(request.discountValue());
        campaign.setMinOrderAmount(request.minOrderAmount());
        campaign.setStartsAt(request.startsAt());
        campaign.setEndsAt(request.endsAt());
        campaign.setMaxRedemptions(request.maxRedemptions());
        campaign.setTotalBudget(request.totalBudget());
        campaign.setCurrency(resolveCurrency(request.currency()));
        campaign.setPerUserLimit(request.perUserLimit() != null ? request.perUserLimit() : 1);
        if (request.status() != null) {
            applyStatus(campaign, request.status());
        }
        return CampaignResponse.from(campaignRepository.save(campaign));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CAMPAIGN_NOT_FOUND, id));
        if (reservationRepository.existsByCampaignIdAndStatusIn(id, COUNTED_STATUSES)) {
            throw BusinessException.of(ErrorCode.CAMPAIGN_IN_USE, campaign.getCode());
        }
        campaign.markDeleted(AuditActor.require(auditorAware::getCurrentAuditor));
        campaignRepository.save(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CampaignUsageResponse> usages(UUID campaignId, Pageable pageable) {
        campaignRepository.findById(campaignId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CAMPAIGN_NOT_FOUND, campaignId));
        return reservationRepository.findByCampaignId(campaignId, pageable)
            .map(CampaignUsageResponse::from);
    }

    private String resolveCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return PlatformCurrency.DEFAULT;
        }
        String normalized = PlatformCurrency.normalize(currency.trim());
        if (!PlatformCurrency.isSupported(normalized)) {
            throw BusinessException.of(PromotionErrorCode.PROMOTION_CURRENCY_UNSUPPORTED, currency);
        }
        return normalized;
    }

    private void applyStatus(Campaign campaign, CampaignStatus status) {
        if (status == CampaignStatus.ACTIVE) {
            campaign.activate();
        } else {
            campaign.deactivate();
        }
    }
}
