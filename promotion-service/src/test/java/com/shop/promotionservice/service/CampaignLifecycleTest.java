package com.shop.promotionservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 7 — idempotent commit / release / releaseCommitted state machine
 * (spec §5.3). Unit tests: mocked repos, frozen Clock, real impl. Branch
 * order is load-bearing: COMMITTED→return, terminal-wrong-way→PRO-7010,
 * expired-pending→PRO-7009 (commit only), then the PENDING/COMMITTED
 * transition + timestamp + event with previousStatus.
 */
@ExtendWith(MockitoExtension.class)
class CampaignLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final String CODE = "SAVE10";

    @Mock CampaignRepository campaignRepository;
    @Mock CouponUsageReservationRepository reservationRepository;
    @Mock PromotionEventPublisher eventPublisher;

    private CampaignReservationServiceImpl service;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CampaignReservationServiceImpl(
            campaignRepository, reservationRepository, eventPublisher, clock);
    }

    private Campaign campaign() {
        return Campaign.builder()
            .id(campaignId)
            .code(CODE)
            .name("Save 10%")
            .discountType("PERCENT")
            .discountValue(new BigDecimal("10.00"))
            .status(CampaignStatus.ACTIVE)
            .build();
    }

    private CouponUsageReservation reservation(UsageStatus status, Instant expiresAt) {
        return CouponUsageReservation.builder()
            .id(reservationId)
            .campaignId(campaignId)
            .userId(UUID.randomUUID())
            .orderId(orderId)
            .orderAmount(new BigDecimal("199.99"))
            .discountAmount(new BigDecimal("20.00"))
            .status(status)
            .expiresAt(expiresAt)
            .reservedAt(NOW.minusSeconds(60))
            .build();
    }

    private void stubFound(CouponUsageReservation r) {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));
    }

    private void stubSaveAndCampaign() {
        when(reservationRepository.save(any(CouponUsageReservation.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));
    }

    private void assertBusinessException(Runnable call, String code, HttpStatus status) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(code);
                assertThat(ex.getStatus()).isEqualTo(status);
            });
    }

    // --- 1. commit(PENDING, not expired) → COMMITTED + committedAt + promotion.committed.v1 ---

    @Test
    @DisplayName("commit PENDING (not expired) → COMMITTED + committedAt + committed event")
    void commitPendingTransitionsAndPublishesCommitted() {
        CouponUsageReservation r = reservation(UsageStatus.PENDING, NOW.plusSeconds(900));
        stubFound(r);
        stubSaveAndCampaign();

        service.commit(reservationId);

        ArgumentCaptor<CouponUsageReservation> captor =
            ArgumentCaptor.forClass(CouponUsageReservation.class);
        verify(reservationRepository).save(captor.capture());
        CouponUsageReservation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(UsageStatus.COMMITTED);
        assertThat(saved.getCommittedAt()).isEqualTo(NOW);
        assertThat(saved.getReleasedAt()).isNull();
        verify(eventPublisher).publishCommitted(any(Campaign.class), same(saved));
    }

    // --- 2. commit(COMMITTED) → no-op (idempotent retry) ---

    @Test
    @DisplayName("commit COMMITTED → idempotent no-op (no save, no event)")
    void commitAlreadyCommittedIsIdempotentNoOp() {
        stubFound(reservation(UsageStatus.COMMITTED, NOW.minusSeconds(1)));

        service.commit(reservationId);

        verify(reservationRepository, never()).save(any(CouponUsageReservation.class));
        verifyNoInteractions(eventPublisher, campaignRepository);
    }

    // --- 3. commit(RELEASED) / commit(EXPIRED) → PRO-7010 (terminal-wrong-way, fail-closed) ---

    @Test
    @DisplayName("commit RELEASED/EXPIRED → PROMOTION_RESERVATION_INVALID_STATE (PRO-7010)")
    void commitTerminalStatesThrowInvalidState() {
        stubFound(reservation(UsageStatus.RELEASED, NOW.plusSeconds(900)));

        assertBusinessException(() -> service.commit(reservationId),
            "PRO-7010", HttpStatus.CONFLICT);

        // EXPIRED branch
        org.mockito.Mockito.reset(reservationRepository);
        stubFound(reservation(UsageStatus.EXPIRED, NOW.minusSeconds(1)));

        assertBusinessException(() -> service.commit(reservationId),
            "PRO-7010", HttpStatus.CONFLICT);

        verify(reservationRepository, never()).save(any(CouponUsageReservation.class));
        verifyNoInteractions(eventPublisher, campaignRepository);
    }

    // --- 4. commit(PENDING expired) → PRO-7009 (boundary: expiresAt == now counts as expired) ---

    @Test
    @DisplayName("commit PENDING past expiresAt → PROMOTION_RESERVATION_EXPIRED (PRO-7009)")
    void commitPendingExpiredThrowsReservationExpired() {
        stubFound(reservation(UsageStatus.PENDING, NOW.minusSeconds(1)));

        assertBusinessException(() -> service.commit(reservationId),
            "PRO-7009", HttpStatus.CONFLICT);

        // boundary: expiresAt exactly now is NOT after now → expired
        org.mockito.Mockito.reset(reservationRepository);
        stubFound(reservation(UsageStatus.PENDING, NOW));

        assertBusinessException(() -> service.commit(reservationId),
            "PRO-7009", HttpStatus.CONFLICT);

        verify(reservationRepository, never()).save(any(CouponUsageReservation.class));
        verifyNoInteractions(eventPublisher, campaignRepository);
    }

    // --- 5. release(PENDING) → RELEASED + releasedAt + event previousStatus="PENDING" ---

    @Test
    @DisplayName("release PENDING → RELEASED + releasedAt + released event previousStatus=PENDING")
    void releasePendingTransitionsAndPublishesPreviousStatusPending() {
        CouponUsageReservation r = reservation(UsageStatus.PENDING, NOW.plusSeconds(900));
        stubFound(r);
        stubSaveAndCampaign();

        service.release(reservationId);

        ArgumentCaptor<CouponUsageReservation> captor =
            ArgumentCaptor.forClass(CouponUsageReservation.class);
        verify(reservationRepository).save(captor.capture());
        CouponUsageReservation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(UsageStatus.RELEASED);
        assertThat(saved.getReleasedAt()).isEqualTo(NOW);
        assertThat(saved.getCommittedAt()).isNull();
        verify(eventPublisher).publishReleased(any(Campaign.class), same(saved), eq("PENDING"));
    }

    // --- 6. release(RELEASED) / release(EXPIRED) → no-op (idempotent retry) ---

    @Test
    @DisplayName("release RELEASED/EXPIRED → idempotent no-op (no save, no event)")
    void releaseTerminalStatesAreIdempotentNoOp() {
        stubFound(reservation(UsageStatus.RELEASED, NOW.plusSeconds(900)));

        service.release(reservationId);

        org.mockito.Mockito.reset(reservationRepository);
        stubFound(reservation(UsageStatus.EXPIRED, NOW.minusSeconds(1)));

        service.release(reservationId);

        verify(reservationRepository, never()).save(any(CouponUsageReservation.class));
        verifyNoInteractions(eventPublisher, campaignRepository);
    }

    // --- 7. release(COMMITTED) → PRO-7010 ---

    @Test
    @DisplayName("release COMMITTED → PROMOTION_RESERVATION_INVALID_STATE (PRO-7010)")
    void releaseCommittedReservationThrowsInvalidState() {
        stubFound(reservation(UsageStatus.COMMITTED, NOW.plusSeconds(900)));

        assertBusinessException(() -> service.release(reservationId),
            "PRO-7010", HttpStatus.CONFLICT);

        verify(reservationRepository, never()).save(any(CouponUsageReservation.class));
        verifyNoInteractions(eventPublisher, campaignRepository);
    }

    // --- 8. releaseCommitted(COMMITTED) → RELEASED + event previousStatus="COMMITTED" ---

    @Test
    @DisplayName("releaseCommitted COMMITTED → RELEASED + released event previousStatus=COMMITTED")
    void releaseCommittedTransitionsAndPublishesPreviousStatusCommitted() {
        CouponUsageReservation r = reservation(UsageStatus.COMMITTED, NOW.plusSeconds(900));
        r.setCommittedAt(NOW.minusSeconds(30));
        stubFound(r);
        stubSaveAndCampaign();

        service.releaseCommitted(reservationId);

        ArgumentCaptor<CouponUsageReservation> captor =
            ArgumentCaptor.forClass(CouponUsageReservation.class);
        verify(reservationRepository).save(captor.capture());
        CouponUsageReservation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(UsageStatus.RELEASED);
        assertThat(saved.getReleasedAt()).isEqualTo(NOW);
        verify(eventPublisher).publishReleased(any(Campaign.class), same(saved), eq("COMMITTED"));
    }

    // --- 9. releaseCommitted(RELEASED/EXPIRED) → no-op; releaseCommitted(PENDING) → PRO-7010 ---

    @Test
    @DisplayName("releaseCommitted RELEASED/EXPIRED → no-op; PENDING → PRO-7010")
    void releaseCommittedTerminalNoOpAndPendingThrowsInvalidState() {
        stubFound(reservation(UsageStatus.RELEASED, NOW.plusSeconds(900)));

        service.releaseCommitted(reservationId);

        org.mockito.Mockito.reset(reservationRepository);
        stubFound(reservation(UsageStatus.EXPIRED, NOW.minusSeconds(1)));

        service.releaseCommitted(reservationId);

        verify(reservationRepository, never()).save(any(CouponUsageReservation.class));

        org.mockito.Mockito.reset(reservationRepository);
        stubFound(reservation(UsageStatus.PENDING, NOW.plusSeconds(900)));

        assertBusinessException(() -> service.releaseCommitted(reservationId),
            "PRO-7010", HttpStatus.CONFLICT);
        verifyNoInteractions(eventPublisher, campaignRepository);
    }

    // --- 10. getState(unknown) → PRO-7008 ---

    @Test
    @DisplayName("getState unknown id → PROMOTION_RESERVATION_NOT_FOUND (PRO-7008)")
    void getStateUnknownThrowsReservationNotFound() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        assertBusinessException(() -> service.getState(reservationId),
            "PRO-7008", HttpStatus.NOT_FOUND);
        verifyNoInteractions(campaignRepository, eventPublisher);
    }

    // --- extras beyond the brief's matrix ---

    @Test
    @DisplayName("commit unknown id → PROMOTION_RESERVATION_NOT_FOUND (PRO-7008, shared guard)")
    void commitUnknownThrowsReservationNotFound() {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.empty());

        assertBusinessException(() -> service.commit(reservationId),
            "PRO-7008", HttpStatus.NOT_FOUND);
        verifyNoInteractions(campaignRepository, eventPublisher);
    }

    @Test
    @DisplayName("getState happy → ReservationResponse projected from campaign + reservation")
    void getStateReturnsProjection() {
        Campaign campaign = campaign();
        CouponUsageReservation r = reservation(UsageStatus.COMMITTED, NOW.plusSeconds(840));
        r.setCommittedAt(NOW.minusSeconds(30));
        stubFound(r);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));

        ReservationResponse response = service.getState(reservationId);

        assertThat(response.reservationId()).isEqualTo(reservationId);
        assertThat(response.campaignId()).isEqualTo(campaignId);
        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(response.finalAmount()).isEqualByComparingTo("179.99");
        assertThat(response.status()).isEqualTo("COMMITTED");
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(840));
    }
}
