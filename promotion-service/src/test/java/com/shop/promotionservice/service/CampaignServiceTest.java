package com.shop.promotionservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.CampaignRequest;
import com.shop.promotionservice.dto.response.CampaignResponse;
import com.shop.promotionservice.dto.response.CampaignUsageResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import com.shop.promotionservice.service.impls.CampaignServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.AuditorAware;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 9 — backoffice campaign CRUD service (spec §4.2). Unit tests: mocked
 * repos + auditor, real impl. Error contract: PRO-7001 not found, PRO-7002
 * duplicate live code (@SQLRestriction makes deleted rows invisible, so a
 * deleted campaign's code is reusable), PRO-7003 campaign with PENDING/COMMITTED
 * usage cannot be deleted. Edits never touch reservation rows.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    private static final String CODE = "SAVE10";

    @Mock CampaignRepository campaignRepository;
    @Mock CouponUsageReservationRepository reservationRepository;
    @Mock AuditorAware<String> auditorAware;

    private CampaignServiceImpl service;

    private final UUID campaignId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new CampaignServiceImpl(campaignRepository, reservationRepository, auditorAware);
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

    private CampaignRequest request() {
        return new CampaignRequest(
            CODE, "Save 10%", "PERCENT", new BigDecimal("10.00"),
            new BigDecimal("50.00"),
            Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
            100, new BigDecimal("1000.00"), 1, CampaignStatus.ACTIVE);
    }

    private void assertBusinessException(Runnable call, String code, HttpStatus status) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(code);
                assertThat(ex.getStatus()).isEqualTo(status);
            });
    }

    // --- 1. create ---

    @Test
    @DisplayName("create duplicate live code → CAMPAIGN_ALREADY_EXISTS (PRO-7002), nothing saved")
    void createDuplicateLiveCodeThrowsAlreadyExists() {
        when(campaignRepository.existsByCode(CODE)).thenReturn(true);

        assertBusinessException(() -> service.create(request()),
            "PRO-7002", HttpStatus.CONFLICT);

        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("create happy → maps every request field onto the saved entity + response")
    void createHappyMapsRequestFields() {
        when(campaignRepository.existsByCode(CODE)).thenReturn(false);
        when(campaignRepository.save(any(Campaign.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        CampaignResponse response = service.create(request());

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        Campaign saved = captor.getValue();
        assertThat(saved.getCode()).isEqualTo(CODE);
        assertThat(saved.getName()).isEqualTo("Save 10%");
        assertThat(saved.getDiscountType()).isEqualTo("PERCENT");
        assertThat(saved.getDiscountValue()).isEqualByComparingTo("10.00");
        assertThat(saved.getMinOrderAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getStartsAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(saved.getEndsAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(saved.getMaxRedemptions()).isEqualTo(100);
        assertThat(saved.getTotalBudget()).isEqualByComparingTo("1000.00");
        assertThat(saved.getPerUserLimit()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.ACTIVE);

        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.status()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    @DisplayName("create with null status + null perUserLimit → defaults INACTIVE + 1")
    void createNullStatusAndPerUserLimitFallBackToDefaults() {
        when(campaignRepository.existsByCode(CODE)).thenReturn(false);
        when(campaignRepository.save(any(Campaign.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        CampaignRequest sparse = new CampaignRequest(
            CODE, "Save 10%", "FIXED", new BigDecimal("5.00"),
            null, null, null, null, null, null, null);

        CampaignResponse response = service.create(sparse);

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CampaignStatus.INACTIVE);
        assertThat(captor.getValue().getPerUserLimit()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(CampaignStatus.INACTIVE);
    }

    // --- 2. update ---

    @Test
    @DisplayName("update unknown id → CAMPAIGN_NOT_FOUND (PRO-7001)")
    void updateUnknownIdThrowsNotFound() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());

        assertBusinessException(() -> service.update(campaignId, request()),
            "PRO-7001", HttpStatus.NOT_FOUND);

        verify(campaignRepository, never()).save(any(Campaign.class));
        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("update code change colliding with another live campaign → PRO-7002")
    void updateCodeCollisionThrowsAlreadyExists() {
        UUID otherId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        Campaign existing = campaign();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(existing));
        when(campaignRepository.existsByCodeAndIdNot("SAVE20", campaignId)).thenReturn(true);
        CampaignRequest renamed = new CampaignRequest(
            "SAVE20", "Save 10%", "PERCENT", new BigDecimal("10.00"),
            null, null, null, null, null, null, CampaignStatus.ACTIVE);

        assertBusinessException(() -> service.update(campaignId, renamed),
            "PRO-7002", HttpStatus.CONFLICT);

        verify(campaignRepository, never()).save(any(Campaign.class));
        // the collision check must not be scoped to some other row
        verify(campaignRepository).existsByCodeAndIdNot("SAVE20", campaignId);
    }

    @Test
    @DisplayName("update without code change → no duplicate-code lookup, fields mutated, no reservation interaction")
    void updateSameCodeMutatesFieldsWithoutReservationInteraction() {
        Campaign existing = campaign();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(existing));
        when(campaignRepository.save(any(Campaign.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        CampaignRequest edits = new CampaignRequest(
            CODE, "Renamed", "FIXED", new BigDecimal("7.50"),
            new BigDecimal("25.00"),
            Instant.parse("2026-08-02T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
            250, new BigDecimal("2000.00"), 2, CampaignStatus.INACTIVE);

        CampaignResponse response = service.update(campaignId, edits);

        verify(campaignRepository, never()).existsByCodeAndIdNot(anyString(), any(UUID.class));
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        Campaign saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(campaignId);
        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.getDiscountType()).isEqualTo("FIXED");
        assertThat(saved.getDiscountValue()).isEqualByComparingTo("7.50");
        assertThat(saved.getMinOrderAmount()).isEqualByComparingTo("25.00");
        assertThat(saved.getMaxRedemptions()).isEqualTo(250);
        assertThat(saved.getTotalBudget()).isEqualByComparingTo("2000.00");
        assertThat(saved.getPerUserLimit()).isEqualTo(2);
        // status INACTIVE via deactivate()
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.INACTIVE);
        assertThat(response.name()).isEqualTo("Renamed");
        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("update INACTIVE campaign with status ACTIVE → activate() (PRO flow via PUT status)")
    void updateActivatesViaStatusField() {
        Campaign existing = Campaign.builder()
            .id(campaignId).code(CODE).name("Save 10%")
            .discountType("PERCENT").discountValue(new BigDecimal("10.00"))
            .status(CampaignStatus.INACTIVE)
            .build();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(existing));
        when(campaignRepository.save(any(Campaign.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        CampaignResponse response = service.update(campaignId, request());

        assertThat(response.status()).isEqualTo(CampaignStatus.ACTIVE);
    }

    // --- 3. delete ---

    @Test
    @DisplayName("delete unknown id → CAMPAIGN_NOT_FOUND (PRO-7001)")
    void deleteUnknownIdThrowsNotFound() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());

        assertBusinessException(() -> service.delete(campaignId),
            "PRO-7001", HttpStatus.NOT_FOUND);

        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("delete with PENDING usage → CAMPAIGN_IN_USE (PRO-7003), no soft-delete")
    void deleteWithPendingUsageThrowsInUse() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));
        when(reservationRepository.existsByCampaignIdAndStatusIn(
                eq(campaignId), eq(List.of(UsageStatus.PENDING, UsageStatus.COMMITTED))))
            .thenReturn(true);

        assertBusinessException(() -> service.delete(campaignId),
            "PRO-7003", HttpStatus.CONFLICT);

        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("delete with COMMITTED usage → CAMPAIGN_IN_USE (PRO-7003)")
    void deleteWithCommittedUsageThrowsInUse() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));
        when(reservationRepository.existsByCampaignIdAndStatusIn(
                eq(campaignId), eq(List.of(UsageStatus.PENDING, UsageStatus.COMMITTED))))
            .thenReturn(true);

        assertBusinessException(() -> service.delete(campaignId),
            "PRO-7003", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("delete free campaign → markDeleted(actor) + save (soft delete, released/expired usage ignored)")
    void deleteFreeCampaignSoftDeletesWithAuditor() {
        Campaign existing = campaign();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(existing));
        when(reservationRepository.existsByCampaignIdAndStatusIn(any(), any())).thenReturn(false);
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("admin-1"));

        service.delete(campaignId);

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        Campaign saved = captor.getValue();
        assertThat(saved.isDeleted()).isTrue();
        assertThat(saved.getDeletedBy()).isEqualTo("admin-1");
        assertThat(saved.getDeletedAt()).isNotNull();
    }

    // --- 4. findAll / findById ---

    @Test
    @DisplayName("findAll with null status → repo.findAll(pageable), mapped to CampaignResponse")
    void findAllNullStatusDelegatesToUnfiltered() {
        when(campaignRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(campaign())));

        Page<CampaignResponse> page = service.findAll(null, pageable);

        verify(campaignRepository).findAll(pageable);
        verify(campaignRepository, never()).findAllByStatus(any(), any());
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(campaignId);
        assertThat(page.getContent().get(0).code()).isEqualTo(CODE);
    }

    @Test
    @DisplayName("findAll with status → repo.findAllByStatus(status, pageable)")
    void findAllWithStatusDelegatesToFiltered() {
        when(campaignRepository.findAllByStatus(CampaignStatus.ACTIVE, pageable))
            .thenReturn(new PageImpl<>(List.of(campaign())));

        Page<CampaignResponse> page = service.findAll(CampaignStatus.ACTIVE, pageable);

        verify(campaignRepository).findAllByStatus(CampaignStatus.ACTIVE, pageable);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).status()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    @DisplayName("findById unknown → CAMPAIGN_NOT_FOUND (PRO-7001)")
    void findByIdUnknownThrowsNotFound() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());

        assertBusinessException(() -> service.findById(campaignId),
            "PRO-7001", HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("findById happy → CampaignResponse projection")
    void findByIdHappyReturnsProjection() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));

        CampaignResponse response = service.findById(campaignId);

        assertThat(response.id()).isEqualTo(campaignId);
        assertThat(response.code()).isEqualTo(CODE);
        assertThat(response.discountValue()).isEqualByComparingTo("10.00");
    }

    // --- 5. usages ---

    @Test
    @DisplayName("usages unknown campaign → CAMPAIGN_NOT_FOUND (existence guard), no reservation query")
    void usagesUnknownCampaignThrowsNotFound() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());

        assertBusinessException(() -> service.usages(campaignId, pageable),
            "PRO-7001", HttpStatus.NOT_FOUND);

        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("usages happy → reservation rows mapped to CampaignUsageResponse")
    void usagesHappyMapsReservations() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));
        Instant reservedAt = Instant.parse("2026-08-30T09:00:00Z");
        Instant committedAt = Instant.parse("2026-08-30T09:05:00Z");
        UUID reservationId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        CouponUsageReservation row = CouponUsageReservation.builder()
            .id(reservationId)
            .campaignId(campaignId)
            .userId(userId)
            .orderId(orderId)
            .orderAmount(new BigDecimal("199.99"))
            .discountAmount(new BigDecimal("20.00"))
            .status(UsageStatus.COMMITTED)
            .expiresAt(reservedAt.plusSeconds(900))
            .reservedAt(reservedAt)
            .committedAt(committedAt)
            .build();
        when(reservationRepository.findByCampaignId(campaignId, pageable))
            .thenReturn(new PageImpl<>(List.of(row)));

        Page<CampaignUsageResponse> page = service.usages(campaignId, pageable);

        assertThat(page.getContent()).hasSize(1);
        CampaignUsageResponse usage = page.getContent().get(0);
        assertThat(usage.reservationId()).isEqualTo(reservationId);
        assertThat(usage.userId()).isEqualTo(userId);
        assertThat(usage.orderId()).isEqualTo(orderId);
        assertThat(usage.orderAmount()).isEqualByComparingTo("199.99");
        assertThat(usage.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(usage.status()).isEqualTo(UsageStatus.COMMITTED);
        assertThat(usage.reservedAt()).isEqualTo(reservedAt);
        assertThat(usage.committedAt()).isEqualTo(committedAt);
        assertThat(usage.releasedAt()).isNull();
    }
}
