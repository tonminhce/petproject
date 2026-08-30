package com.shop.promotionservice.service;

import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 11 — TTL sweep + retention purge (spec §5.4). Mirrors inventory's
 * ReservationCleanupSchedulerTest: batch loop with flush+clear per batch.
 * Promotion deltas: status flip only (no inventory rows, no cache, no events) —
 * quota returns implicitly because per-user counts are by-status.
 */
@ExtendWith(MockitoExtension.class)
class ReservationCleanupSchedulerTest {

    @Mock CouponUsageReservationRepository reservationRepository;
    @Mock EntityManager entityManager;
    @InjectMocks ReservationCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        // @Value and @PersistenceContext fields are not injected by Mockito - set explicitly
        ReflectionTestUtils.setField(scheduler, "batchSize", 500);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 30);
        ReflectionTestUtils.setField(scheduler, "entityManager", entityManager);
    }

    private CouponUsageReservation pending(long secondsOverdue) {
        return CouponUsageReservation.builder()
            .id(java.util.UUID.randomUUID())
            .campaignId(java.util.UUID.randomUUID())
            .userId(java.util.UUID.randomUUID())
            .orderId(java.util.UUID.randomUUID())
            .status(UsageStatus.PENDING)
            .expiresAt(Instant.now().minusSeconds(secondsOverdue))
            .reservedAt(Instant.now().minusSeconds(secondsOverdue + 900))
            .build();
    }

    @Test
    void sweep_flipsPendingToExpired_batched() {
        CouponUsageReservation first = pending(60);
        CouponUsageReservation second = pending(120);
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), eq(PageRequest.of(0, 500))))
            .thenReturn(List.of(first, second))
            .thenReturn(List.of());

        scheduler.releaseAllExpiredReservations();

        assertThat(first.getStatus()).isEqualTo(UsageStatus.EXPIRED);
        assertThat(second.getStatus()).isEqualTo(UsageStatus.EXPIRED);
        // flush+clear per non-empty batch to bound the persistence context
        verify(entityManager, times(1)).flush();
        verify(entityManager, times(1)).clear();
    }

    @Test
    void sweep_noExpired_noWrites() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(
                eq(UsageStatus.PENDING), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.releaseAllExpiredReservations();

        verify(reservationRepository, never()).saveAll(any());
        verify(entityManager, never()).flush();
        verify(entityManager, never()).clear();
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
            .id(java.util.UUID.randomUUID())
            .campaignId(java.util.UUID.randomUUID())
            .userId(java.util.UUID.randomUUID())
            .orderId(java.util.UUID.randomUUID())
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
        verify(entityManager, times(1)).flush();
        verify(entityManager, times(1)).clear();
    }

    @Test
    void purge_noTerminalRows_noDeletes() {
        when(reservationRepository.findTerminalBefore(
                anyCollection(), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        scheduler.purgeOldTerminalReservations();

        verify(reservationRepository, never()).deleteAllInBatch(any());
        verify(entityManager, never()).flush();
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
