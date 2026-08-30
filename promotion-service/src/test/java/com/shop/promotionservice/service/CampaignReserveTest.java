package com.shop.promotionservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 6 — reserve validation chain (spec §5.1) + version-touch race guard
 * (§5.2). Unit tests: mocked repos, frozen Clock, real DiscountCalculator.
 * Gate order is load-bearing: NOT_FOUND → NOT_ACTIVE → MIN_ORDER → PER_USER
 * → MAX_REDEMPTIONS → BUDGET.
 */
@ExtendWith(MockitoExtension.class)
class CampaignReserveTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final long TTL_SECONDS = 900L;
    private static final String CODE = "SAVE10";
    /** Spec §5.1 "PENDING, CONFIRMED" — CONFIRMED is COMMITTED in the landed UsageStatus enum. */
    private static final List<UsageStatus> COUNTED_STATUSES =
        List.of(UsageStatus.PENDING, UsageStatus.COMMITTED);

    @Mock CampaignRepository campaignRepository;
    @Mock CouponUsageReservationRepository reservationRepository;
    @Mock PromotionEventPublisher eventPublisher;

    private CampaignReservationServiceImpl service;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CampaignReservationServiceImpl(
            campaignRepository, reservationRepository, eventPublisher, clock);
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
        when(campaignRepository.saveAndFlush(any(Campaign.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepository.save(any(CouponUsageReservation.class)))
            .thenAnswer(inv -> {
                CouponUsageReservation r = inv.getArgument(0);
                r.setId(UUID.randomUUID());
                return r;
            });
    }

    // --- 1. unknown / deleted code → PRO-7001 (@SQLRestriction hides deleted) ---

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

    // --- 2. INACTIVE / before starts_at / after ends_at → PRO-7004 ---

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
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }

    @Test
    @DisplayName("after ends_at → CAMPAIGN_NOT_ACTIVE (PRO-7004)")
    void afterEndsAtThrowsNotActive() {
        when(campaignRepository.findByCode(CODE))
            .thenReturn(Optional.of(baseCampaign().endsAt(NOW.minusSeconds(1)).build()));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7004"));
    }

    // --- 3. orderAmount < min_order_amount → PRO-7005 (null min → skip) ---

    @Test
    @DisplayName("orderAmount below min_order_amount → MIN_ORDER_AMOUNT_NOT_MET (PRO-7005)")
    void belowMinOrderAmountThrowsMinOrderNotMet() {
        when(campaignRepository.findByCode(CODE))
            .thenReturn(Optional.of(baseCampaign().minOrderAmount(new BigDecimal("200.00")).build()));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7005");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
        verifyNoInteractions(reservationRepository, eventPublisher);
    }

    // --- 4. per-user limit → PRO-7006 (per_user_limit=0 → skip check) ---

    @Test
    @DisplayName("user at per_user_limit → PER_USER_LIMIT_EXCEEDED (PRO-7006)")
    void perUserLimitReachedThrowsPerUserLimitExceeded() {
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(activeCampaign()));
        when(reservationRepository.countByCampaignIdAndUserIdAndStatusIn(
            campaignId, userId, COUNTED_STATUSES)).thenReturn(1L);

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7006");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
        verify(reservationRepository).countByCampaignIdAndUserIdAndStatusIn(
            campaignId, userId, COUNTED_STATUSES);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("per_user_limit=0 → check skipped (no per-user count query)")
    void zeroPerUserLimitSkipsLimitCheck() {
        Campaign campaign = baseCampaign().perUserLimit(0).build();
        stubHappyPath(campaign);

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        assertThat(response).isNotNull();
        verify(reservationRepository, never()).countByCampaignIdAndUserIdAndStatusIn(
            any(), any(), any());
    }

    // --- 5. max_redemptions reached → PRO-7007 ---

    @Test
    @DisplayName("max_redemptions reached (PENDING+COMMITTED count) → BUDGET_EXHAUSTED (PRO-7007)")
    void maxRedemptionsReachedThrowsBudgetExhausted() {
        Campaign campaign = baseCampaign().maxRedemptions(10).build();
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        when(reservationRepository.countByCampaignIdAndStatusIn(campaignId, COUNTED_STATUSES))
            .thenReturn(10L);

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("PRO-7007");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
        verify(reservationRepository, never()).sumDiscountByCampaignIdAndStatusIn(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    // --- 6. budget exceeded → PRO-7007 ---

    @Test
    @DisplayName("sumDiscount(PENDING+COMMITTED) + newDiscount > total_budget → BUDGET_EXHAUSTED (PRO-7007)")
    void budgetExceededThrowsBudgetExhausted() {
        Campaign campaign = baseCampaign().totalBudget(new BigDecimal("100.00")).build();
        when(campaignRepository.findByCode(CODE)).thenReturn(Optional.of(campaign));
        // 199.99 x 10% = 20.00 new discount; 80.01 held + 20.00 > 100.00
        when(reservationRepository.sumDiscountByCampaignIdAndStatusIn(campaignId, COUNTED_STATUSES))
            .thenReturn(new BigDecimal("80.01"));

        assertThatThrownBy(() -> service.reserve(CODE, request("199.99")))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7007"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("sumDiscount + newDiscount == total_budget → allowed (<= passes)")
    void budgetExactlyMetIsAllowed() {
        Campaign campaign = baseCampaign().totalBudget(new BigDecimal("100.00")).build();
        stubHappyPath(campaign);
        when(reservationRepository.sumDiscountByCampaignIdAndStatusIn(campaignId, COUNTED_STATUSES))
            .thenReturn(new BigDecimal("80.00"));

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        assertThat(response).isNotNull();
    }

    // --- 7. happy path: frozen amounts, PENDING row, ttl expiry, version-touch, event ---

    @Test
    @DisplayName("happy: saves PENDING row frozen at now+ttl, touches campaign version, publishes, returns frozen amounts")
    void happyPathSavesPendingRowAndReturnsFrozenAmounts() {
        Campaign campaign = activeCampaign();
        stubHappyPath(campaign);

        ReservationResponse response = service.reserve(CODE, request("199.99"));

        // version-touch race guard (§5.2): campaign marked dirty at `now`, flushed
        assertThat(campaign.getUpdatedAt()).isEqualTo(NOW);
        verify(campaignRepository).saveAndFlush(same(campaign));

        // counts consulted with exactly PENDING+COMMITTED
        verify(reservationRepository).countByCampaignIdAndUserIdAndStatusIn(
            campaignId, userId, COUNTED_STATUSES);

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
        Campaign campaign = activeCampaign(); // min/max/budget all null
        stubHappyPath(campaign);

        ReservationResponse response = service.reserve(CODE, request("0.01"));

        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
        verify(reservationRepository, never()).countByCampaignIdAndStatusIn(any(), any());
        verify(reservationRepository, never()).sumDiscountByCampaignIdAndStatusIn(any(), any());
    }
}
