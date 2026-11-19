package com.shop.promotionservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CampaignUserUsageRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import com.shop.promotionservice.service.impls.CampaignReservationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignReserveTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final long TTL_SECONDS = 900L;
    private static final String CODE = "SAVE10";

    @Mock CampaignRepository campaignRepository;
    @Mock CouponUsageReservationRepository reservationRepository;
    @Mock CampaignUserUsageRepository campaignUserUsageRepository;
    @Mock PromotionEventPublisher eventPublisher;

    private CampaignReservationServiceImpl service;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CampaignReservationServiceImpl(
            campaignRepository, reservationRepository, campaignUserUsageRepository, eventPublisher, clock);
        ReflectionTestUtils.setField(service, "reservationTtlSeconds", TTL_SECONDS);
    }

    private Campaign.CampaignBuilder baseCampaign() {
        return Campaign.builder()
            .id(campaignId)
            .code(CODE)
            .name("Save 10%")
            .discountType("PERCENT")
            .discountValue(new BigDecimal("10.00"))
            .startsAt(NOW.minusSeconds(3600))
            .endsAt(NOW.plusSeconds(86400))
            .perUserLimit(1)
            .status(CampaignStatus.ACTIVE);
    }

    private Campaign activeCampaign() {
        return baseCampaign().build();
    }

    private ReserveRequest request(String orderAmount) {
        return new ReserveRequest(userId, orderId, new BigDecimal(orderAmount));
    }

    private void stubHappyPath(Campaign campaign) {
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        when(campaignRepository.claimQuota(any(UUID.class), any(BigDecimal.class))).thenReturn(1);
        when(reservationRepository.save(any(CouponUsageReservation.class)))
            .thenAnswer(inv -> {
                CouponUsageReservation r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                return r;
            });
    }

    private void stubPerUserQuota() {
        when(campaignUserUsageRepository.claimQuota(any(UUID.class), any(UUID.class), anyInt()))
            .thenReturn(1);
    }

    @Test
    @DisplayName("same order retry returns existing reservation without duplicate write or event")
    void sameOrderRetryIsIdempotent() {
        Campaign campaign = activeCampaign();
        CouponUsageReservation existing = CouponUsageReservation.builder()
            .id(UUID.randomUUID())
            .campaignId(campaignId)
            .userId(userId)
            .orderId(orderId)
            .orderAmount(new BigDecimal("199.99"))
            .discountAmount(new BigDecimal("20.00"))
            .status(UsageStatus.PENDING)
            .expiresAt(NOW.plusSeconds(TTL_SECONDS))
            .reservedAt(NOW)
            .build();
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        assertThat(response.reservationId()).isEqualTo(existing.getId());
        verify(reservationRepository).findByOrderId(orderId);
        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("same order with changed request is rejected as duplicate")
    void sameOrderChangedRequestIsRejected() {
        Campaign campaign = activeCampaign();
        CouponUsageReservation existing = CouponUsageReservation.builder()
            .id(UUID.randomUUID()).campaignId(campaignId).userId(userId).orderId(orderId)
            .orderAmount(new BigDecimal("199.99")).discountAmount(new BigDecimal("20.00"))
            .status(UsageStatus.PENDING).expiresAt(NOW.plusSeconds(TTL_SECONDS)).reservedAt(NOW).build();
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reserve(CODE, request("299.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7012"));
        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }


    @Test
    @DisplayName("unknown code → CAMPAIGN_NOT_FOUND (PRO-7001)")
    void unknownCodeThrowsCampaignNotFound() {
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7001");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            });
        verifyNoInteractions(reservationRepository, eventPublisher);
    }


    @Test
    @DisplayName("INACTIVE campaign → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void inactiveCampaignThrowsNotActive() {
        when(campaignRepository.findByCode(CODE))
            .thenReturn(Optional.of(baseCampaign().status(CampaignStatus.INACTIVE).build()));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7004");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
        verifyNoInteractions(reservationRepository, eventPublisher);
    }

    @Test
    @DisplayName("before starts_at → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void beforeStartsAtThrowsNotActive() {
        when(campaignRepository.findByCode(CODE))
            .thenReturn(Optional.of(baseCampaign().startsAt(NOW.plusSeconds(1)).build()));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }

    @Test
    @DisplayName("after ends_at → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void afterEndsAtThrowsNotActive() {
        when(campaignRepository.findByCode(CODE))
            .thenReturn(Optional.of(baseCampaign().endsAt(NOW.minusSeconds(1)).build()));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }


    @Test
    @DisplayName("orderAmount below min_order_amount → MIN_ORDER_AMOUNT_NOT_MET (PRO-7005)")
    void belowMinOrderAmountThrowsMinOrderNotMet() {
        when(campaignRepository.findByCode(CODE))
            .thenReturn(Optional.of(baseCampaign().minOrderAmount(new BigDecimal("200.00")).build()));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7005"));
        verifyNoInteractions(reservationRepository, eventPublisher);
    }


    @Test
    @DisplayName("per-user quota claim refused → PER_USER_LIMIT_EXCEEDED (PRO-7006)")
    void perUserLimitReachedThrowsPerUserLimitExceeded() {
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(activeCampaign()));
        when(campaignUserUsageRepository.claimQuota(campaignId, userId, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7006");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
        verify(campaignUserUsageRepository).claimQuota(campaignId, userId, 1);
        verify(campaignRepository, never()).claimQuota(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("per_user_limit=0 → check skipped (no per-user quota claim)")
    void zeroPerUserLimitSkipsLimitCheck() {
        Campaign campaign = baseCampaign().perUserLimit(0).build();
        stubHappyPath(campaign);

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        assertThat(response).isNotNull();
        verify(campaignUserUsageRepository, never()).claimQuota(any(), any(), anyInt());
    }


    @Test
    @DisplayName("campaign quota claim refused → BUDGET_EXHAUSTED (PRO-7007)")
    void maxRedemptionsReachedThrowsBudgetExhausted() {
        Campaign campaign = baseCampaign().maxRedemptions(10).build();
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        when(campaignUserUsageRepository.claimQuota(campaignId, userId, 1)).thenReturn(1);
        when(campaignRepository.claimQuota(campaignId, new BigDecimal("20.00"))).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7007");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
        verify(campaignRepository).claimQuota(campaignId, new BigDecimal("20.00"));
        verifyNoInteractions(eventPublisher);
    }


    @Test
    @DisplayName("campaign quota claim refused when the budget is exhausted → BUDGET_EXHAUSTED (PRO-7007)")
    void budgetExceededThrowsBudgetExhausted() {
        Campaign campaign = baseCampaign().totalBudget(new BigDecimal("100.00")).build();
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        when(campaignUserUsageRepository.claimQuota(campaignId, userId, 1)).thenReturn(1);
        when(campaignRepository.claimQuota(campaignId, new BigDecimal("20.00"))).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7007"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("campaign quota claim granted when the budget is exactly met → allowed")
    void budgetExactlyMetIsAllowed() {
        Campaign campaign = baseCampaign().totalBudget(new BigDecimal("100.00")).build();
        stubHappyPath(campaign);
        stubPerUserQuota();

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        assertThat(response).isNotNull();
    }


    @Test
    @DisplayName("happy: saves PENDING row frozen at now+ttl, claims both quotas, publishes, returns frozen amounts")
    void happyPathSavesPendingRowAndReturnsFrozenAmounts() {
        Campaign campaign = activeCampaign();
        stubHappyPath(campaign);
        stubPerUserQuota();

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        verify(campaignUserUsageRepository).claimQuota(campaignId, userId, 1);
        verify(campaignRepository).claimQuota(campaignId, new BigDecimal("20.00"));
        verify(campaignRepository, never()).saveAndFlush(any(Campaign.class));

        org.mockito.ArgumentCaptor<CouponUsageReservation> captor =
            org.mockito.ArgumentCaptor.forClass(CouponUsageReservation.class);
        verify(reservationRepository).save(captor.capture());
        CouponUsageReservation saved = captor.getValue();
        assertThat(saved.getCampaignId()).isEqualTo(campaignId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getOrderAmount()).isEqualByComparingTo("199.99");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(saved.getStatus()).isEqualTo(UsageStatus.PENDING);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusSeconds(TTL_SECONDS));
        assertThat(saved.getReservedAt()).isEqualTo(NOW);

        verify(eventPublisher).publishReserved(campaign, saved);

        assertThat(response.reservationId()).isEqualTo(saved.getId());
        assertThat(response.campaignId()).isEqualTo(campaignId);
        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(response.finalAmount()).isEqualByComparingTo("179.99");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(TTL_SECONDS));
    }

    @Test
    @DisplayName("null min_order_amount / max_redemptions / total_budget → all caps skipped")
    void nullCapsAreSkipped() {
        Campaign campaign = activeCampaign();
        stubHappyPath(campaign);
        stubPerUserQuota();

        ReservationResponse response = service.reserve(CODE, request("0.01"));

        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
        verify(campaignRepository).claimQuota(campaignId, new BigDecimal("0.00"));
        verify(reservationRepository, never()).countByCampaignIdAndStatusIn(any(), any());
        verify(reservationRepository, never()).sumDiscountByCampaignIdAndStatusIn(any(), any());
    }
}
