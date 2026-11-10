package com.shop.promotionservice.service;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 11 — TTL sweep + retention purge (spec §5.4). Mirrors payment's
 * WebhookRetrySchedulerTest: a no-op PlatformTransactionManager stub, because
 * the assertions are about batch semantics, not transaction propagation
 * (verified by integration tests on a real DB). A16 shape: one transaction
 * per batch (TransactionTemplate), no persistence-context flush/clear — a
 * mid-cycle failure rolls back only the failing batch and terminates.
 *
 * <p>The sweep now CLAIMS each row with a conditional UPDATE
 * ({@code status = PENDING} -> {@code status = EXPIRED}) instead of flipping
 * the status on a dirty-checked entity. A row that another writer already
 * transitioned affects zero rows and is skipped, so a COMMITTED redemption can
 * never be reverted to EXPIRED (which would stop it being counted against the
 * per-user limit and the campaign budget).</p>
 */
@ExtendWith(MockitoExtension.class)
class ReservationCleanupSchedulerTest {

    @Mock CouponUsageReservationRepository reservationRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock PromotionEventPublisher eventPublisher;

    private ReservationCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                // No-op — TransactionTemplate needs a non-null status to commit.
                return new SimpleTransactionStatus(true);
            }
            @Override
            public void commit(TransactionStatus status) { }
            @Override
            public void rollback(TransactionStatus status) { }
        };
        scheduler = new ReservationCleanupScheduler(
                reservationRepository, campaignRepository, eventPublisher, txManager);
        // @Value fields are not injected outside Spring - set explicitly
        ReflectionTestUtils.setField(scheduler, "batchSize", 500);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 30);
    }

    private CouponUsageReservation pending(long secondsOverdue) {
        return CouponUsageReservation.builder()
            .id(UUID.randomUUID())
            .campaignId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .orderId(UUID.randomUUID())
            .status(UsageStatus.PENDING)
            .expiresAt(Instant.now().minusSeconds(secondsOverdue))
            .reservedAt(Instant.now().minusSeconds(secondsOverdue + 900))
            .build();
    }

    @Test
    void sweep_claimsPendingRows_batched() {
        CouponUsageReservation first = pending(60);
        CouponUsageReservation second = pending(120);
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), eq(PageRequest.of(0, 500))))
            .thenReturn(List.of(first, second))
            .thenReturn(List.of());
        when(reservationRepository.claimForExpiry(
                any(UUID.class), eq(UsageStatus.PENDING), eq(UsageStatus.EXPIRED), any(Instant.class)))
            .thenReturn(1);
        when(campaignRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        scheduler.releaseAllExpiredReservations();

        // dirty-checked entity, so the sweep must claim every candidate.
        verify(reservationRepository).claimForExpiry(
            eq(first.getId()), eq(UsageStatus.PENDING), eq(UsageStatus.EXPIRED), any(Instant.class));
        verify(reservationRepository).claimForExpiry(
            eq(second.getId()), eq(UsageStatus.PENDING), eq(UsageStatus.EXPIRED), any(Instant.class));
        // batched: drain until the page comes back empty
        verify(reservationRepository, times(2)).findByStatusAndExpiresAtBefore(
            eq(UsageStatus.PENDING), any(Instant.class), eq(PageRequest.of(0, 500)));
    }

    @Test
    void sweep_skipsRowsAlreadyClaimedByAnotherWriter() {
        CouponUsageReservation raced = pending(60);
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), eq(PageRequest.of(0, 500))))
            .thenReturn(List.of(raced))
            .thenReturn(List.of());
        // The claim lost the race - the row is no longer PENDING.
        when(reservationRepository.claimForExpiry(
                eq(raced.getId()), eq(UsageStatus.PENDING), eq(UsageStatus.EXPIRED), any(Instant.class)))
            .thenReturn(0);

        scheduler.releaseAllExpiredReservations();

        verify(eventPublisher, never()).publishReleased(any(), any(), anyString());
        verify(campaignRepository, never()).findById(any());
    }

    @Test
    void sweep_publishesReleasedEventForEachExpiredReservation() {
        CouponUsageReservation expired = pending(60);
        var campaign = com.shop.promotionservice.entity.Campaign.builder()
            .id(expired.getCampaignId())
            .code("SAVE10")
            .name("Save 10")
            .discountType("PERCENT")
            .discountValue(new java.math.BigDecimal("10"))
            .build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), eq(PageRequest.of(0, 500))))
            .thenReturn(List.of(expired))
            .thenReturn(List.of());
        when(reservationRepository.claimForExpiry(
                eq(expired.getId()), eq(UsageStatus.PENDING), eq(UsageStatus.EXPIRED), any(Instant.class)))
            .thenReturn(1);
        when(campaignRepository.findById(expired.getCampaignId())).thenReturn(Optional.of(campaign));

        scheduler.releaseAllExpiredReservations();

        verify(eventPublisher).publishReleased(campaign, expired, UsageStatus.PENDING.name());
        assertThat(expired.getReleasedAt()).isNotNull();
    }

    @Test
    void sweep_noExpired_noWrites() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.releaseAllExpiredReservations();

        verify(reservationRepository, never()).saveAll(any());
        verify(reservationRepository, never()).claimForExpiry(any(), any(), any(), any());
    }

    @Test
    void sweep_failureInBatch_terminatesWithoutLoopingForever() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(pending(60)))
            .thenThrow(new IllegalStateException("db glitch"));

        assertThatCode(() -> scheduler.releaseAllExpiredReservations())
            .doesNotThrowAnyException();

        verify(reservationRepository, times(2)).findByStatusAndExpiresAtBefore(
            any(UsageStatus.class), any(Instant.class), any(PageRequest.class));
    }

    @Test
    void purge_deletesTerminalRowsOlderThanCutoff_batched() {
        CouponUsageReservation oldReleased = CouponUsageReservation.builder()
            .id(UUID.randomUUID())
            .campaignId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .orderId(UUID.randomUUID())
            .status(UsageStatus.RELEASED)
            .expiresAt(Instant.now().minus(31, ChronoUnit.DAYS))
            .reservedAt(Instant.now().minus(31, ChronoUnit.DAYS))
            .releasedAt(Instant.now().minus(31, ChronoUnit.DAYS))
            .build();
        Instant before = Instant.now();
        when(reservationRepository.findTerminalBefore(
                eq(List.of(UsageStatus.RELEASED, UsageStatus.EXPIRED)),
                any(Instant.class), eq(PageRequest.of(0, 500))))
            .thenReturn(List.of(oldReleased))
            .thenReturn(List.of());

        scheduler.purgeOldTerminalReservations();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(reservationRepository, times(2)).findTerminalBefore(
            eq(List.of(UsageStatus.RELEASED, UsageStatus.EXPIRED)),
            cutoff.capture(), eq(PageRequest.of(0, 500)));
        Instant value = cutoff.getAllValues().get(0);
        // cutoff = now - retentionDays(30)
        assertThat(value).isAfter(before.minus(31, ChronoUnit.DAYS));
        assertThat(value).isBefore(before.minus(29, ChronoUnit.DAYS));
        verify(reservationRepository).deleteAllInBatch(List.of(oldReleased));
    }

    @Test
    void purge_noTerminalRows_noDeletes() {
        when(reservationRepository.findTerminalBefore(
                anyCollection(), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.purgeOldTerminalReservations();

        verify(reservationRepository, never()).deleteAllInBatch(any());
    }

    @Test
    void purge_failureInBatch_terminatesWithoutLoopingForever() {
        when(reservationRepository.findTerminalBefore(
                anyCollection(), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(pending(1)))
            .thenThrow(new IllegalStateException("db glitch"));

        assertThatCode(() -> scheduler.purgeOldTerminalReservations())
            .doesNotThrowAnyException();

        verify(reservationRepository, times(2)).findTerminalBefore(
            anyCollection(), any(Instant.class), any(PageRequest.class));
    }
}
