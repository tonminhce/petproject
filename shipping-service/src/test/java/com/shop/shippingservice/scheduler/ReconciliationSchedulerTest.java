package com.shop.shippingservice.scheduler;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.ShippingMetrics;
import com.shop.shippingservice.service.ShipmentWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHIPMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final long AUTO_DELIVER_DAYS = 7;

    @Mock ShipmentRepository repository;
    @Mock ShipmentWriter writer;

    @Captor ArgumentCaptor<Collection<ShipmentStatus>> statusesCaptor;

    private SimpleMeterRegistry meterRegistry;
    private ReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new ReconciliationScheduler(repository, writer,
                new ShippingMetrics(meterRegistry), Clock.fixed(NOW, ZoneOffset.UTC), AUTO_DELIVER_DAYS);
    }

    private Shipment shipment(ShipmentStatus status, Instant lastCarrierUpdate) {
        return Shipment.builder()
                .id(SHIPMENT_ID)
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .status(status)
                .lastCarrierUpdate(lastCarrierUpdate)
                .build();
    }

    @Test
    void inFlightPastCutoff_flipsToDeliveredFlaggedAutoSavedDeliveredAndCounted() {
        Shipment shipment = shipment(ShipmentStatus.IN_TRANSIT, NOW.minus(Duration.ofDays(8)));
        when(repository.findByStatusInAndLastCarrierUpdateBefore(any(), any(Instant.class)))
                .thenReturn(List.of(shipment));
        when(writer.saveDelivered(shipment, true)).thenReturn(shipment);

        scheduler.reconcile();

        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(writer).saveDelivered(captor.capture(), eq(true));
        Shipment saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(saved.getPreviousStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(saved.isAutoDelivered()).isTrue();
        assertThat(saved.getDeliveredAt()).isEqualTo(NOW);
        assertThat(meterRegistry.counter("shipping.delivered.count", "auto", "true").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("shipping.delivered.count", "auto", "false").count()).isZero();
        assertThat(meterRegistry.get("shipping.stale.inflight").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void pickedUpPastCutoff_alsoFlipsToDelivered() {
        Shipment shipment = shipment(ShipmentStatus.PICKED_UP, NOW.minus(Duration.ofDays(30)));
        when(repository.findByStatusInAndLastCarrierUpdateBefore(any(), any(Instant.class)))
                .thenReturn(List.of(shipment));
        when(writer.saveDelivered(shipment, true)).thenReturn(shipment);

        scheduler.reconcile();

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipment.getPreviousStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(shipment.isAutoDelivered()).isTrue();
    }

    @Test
    void cutoffIsNowMinusAutoDeliverDays_soUnderCutoffRowsNeverSurface() {
        when(repository.findByStatusInAndLastCarrierUpdateBefore(any(), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.reconcile();

        verify(repository).findByStatusInAndLastCarrierUpdateBefore(any(),
                eq(NOW.minus(Duration.ofDays(AUTO_DELIVER_DAYS))));
    }

    @Test
    void queriesOnlyInFlightStatuses_createdNeverTouched() {
        when(repository.findByStatusInAndLastCarrierUpdateBefore(any(), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.reconcile();

        verify(repository).findByStatusInAndLastCarrierUpdateBefore(statusesCaptor.capture(), any());
        assertThat(statusesCaptor.getValue()).containsExactlyInAnyOrder(
                ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void emptyRepository_noWritesNoPublishNoCounts() {
        when(repository.findByStatusInAndLastCarrierUpdateBefore(any(), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.reconcile();

        verifyNoInteractions(writer);
        assertThat(meterRegistry.counter("shipping.delivered.count", "auto", "true").count()).isZero();
        assertThat(meterRegistry.get("shipping.stale.inflight").gauge().value()).isZero();
    }
}
