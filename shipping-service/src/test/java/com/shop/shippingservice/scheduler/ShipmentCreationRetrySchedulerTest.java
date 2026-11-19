package com.shop.shippingservice.scheduler;

import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.carrier.CarrierFactory;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentCreationStatus;
import com.shop.shippingservice.entity.ShipmentCreationTask;
import com.shop.shippingservice.service.ShipmentCreationService;
import com.shop.shippingservice.service.ShipmentCreationWriter;
import com.shop.shippingservice.service.ShippingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentCreationRetrySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Mock ShipmentCreationWriter writer;
    @Mock ShipmentCreationService creationService;
    @Mock CarrierFactory carrierFactory;
    @Mock CarrierAdapter adapter;

    private SimpleMeterRegistry meterRegistry;
    private ShipmentCreationRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new ShipmentCreationRetryScheduler(writer, creationService, carrierFactory,
                Clock.fixed(NOW, ZoneOffset.UTC), new ShippingMetrics(meterRegistry), 50, 60_000L);
    }

    private ShipmentCreationTask task() {
        return ShipmentCreationTask.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .carrier(Carrier.MANUAL)
                .status(ShipmentCreationStatus.PENDING)
                .attemptCount(1)
                .nextAttemptAt(NOW)
                .build();
    }

    @Test
    void dueTask_isClaimedAndRetriedThroughTheCreationService() {
        ShipmentCreationTask task = task();
        when(writer.claimDue(eq(ShipmentCreationStatus.PENDING), eq(NOW), any(Instant.class), eq(50)))
                .thenReturn(List.of(task));
        when(carrierFactory.getCarrier(Carrier.MANUAL)).thenReturn(adapter);
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);

        scheduler.retryPendingCreations();

        verify(creationService).attempt(task, adapter);
    }

    @Test
    void aCarrierThatIsNotConfigured_parksTheTaskInsteadOfCallingTheCarrier() {
        ShipmentCreationTask task = task();
        when(writer.claimDue(eq(ShipmentCreationStatus.PENDING), eq(NOW), any(Instant.class), eq(50)))
                .thenReturn(List.of(task));
        when(carrierFactory.getCarrier(Carrier.MANUAL)).thenReturn(adapter);
        when(adapter.carrier()).thenReturn(Carrier.NOOP);

        scheduler.retryPendingCreations();

        verify(creationService, never()).attempt(any(), any());
        verify(writer).failPermanently(eq(task.getId()), eq(2), anyString());
        assertThat(meterRegistry.counter("shipping.shipment.creation.failed.permanent.count",
                "carrier", "MANUAL").count()).isEqualTo(1.0);
    }

    @Test
    void aFailingClaimDoesNotEscapeTheScheduler() {
        when(writer.claimDue(eq(ShipmentCreationStatus.PENDING), eq(NOW), any(Instant.class), eq(50)))
                .thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> scheduler.retryPendingCreations()).doesNotThrowAnyException();

        verify(creationService, never()).attempt(any(), any());
        verify(writer, never()).failPermanently(any(), anyInt(), anyString());
    }
}
