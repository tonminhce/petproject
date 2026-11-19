package com.shop.shippingservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentCreationStatus;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentCreationTask;
import com.shop.shippingservice.repository.ShipmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentCreationServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SHIPMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Mock ShipmentCreationWriter writer;
    @Mock ShipmentRepository shipmentRepository;
    @Mock CarrierAdapter adapter;

    private SimpleMeterRegistry meterRegistry;
    private ShipmentCreationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ShipmentCreationService(writer, shipmentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), new ShippingMetrics(meterRegistry));
    }

    private ShipmentCreationTask task(int attemptCount) {
        return ShipmentCreationTask.builder()
                .id(TASK_ID)
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .status(ShipmentCreationStatus.PENDING)
                .attemptCount(attemptCount)
                .nextAttemptAt(NOW)
                .build();
    }

    @Test
    void duplicateConfirmedEvent_secondClaimIsRejectedAndTheCarrierIsNotCalledAgain() {
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW)))
                .thenThrow(new DataIntegrityViolationException("uk_shipment_creation_task_order"));
        when(writer.findByOrderId(ORDER_ID)).thenReturn(null);

        service.createForOrder(ORDER_ID, adapter);

        verify(adapter, never()).createShipment(any());
        verify(writer, never()).complete(any(), any());
    }

    @Test
    void aRemovedShipment_reopensTheCreationClaimSoTheOrderCanBeShippedAgain() {
        ShipmentCreationTask completed = ShipmentCreationTask.builder()
                .id(TASK_ID)
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .status(ShipmentCreationStatus.COMPLETED)
                .attemptCount(1)
                .shipmentId(SHIPMENT_ID)
                .trackingNumber("TRK-OLD")
                .build();
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW)))
                .thenThrow(new DataIntegrityViolationException("uk_shipment_creation_task_order"));
        when(writer.findByOrderId(ORDER_ID)).thenReturn(completed);
        when(writer.reopen(eq(TASK_ID), eq(Carrier.MANUAL), eq(NOW))).thenReturn(completed);
        when(shipmentRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(adapter.createShipment(ORDER_ID))
                .thenReturn(new CarrierAdapter.ShipmentDraft("TRK-NEW", ShipmentStatus.CREATED));

        service.createForOrder(ORDER_ID, adapter);

        verify(writer).reopen(TASK_ID, Carrier.MANUAL, NOW);
        verify(adapter).createShipment(ORDER_ID);
    }

    @Test
    void aLiveShipment_blocksTheCreationClaimFromBeingReopened() {
        ShipmentCreationTask completed = ShipmentCreationTask.builder()
                .id(TASK_ID)
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .status(ShipmentCreationStatus.COMPLETED)
                .attemptCount(1)
                .shipmentId(SHIPMENT_ID)
                .trackingNumber("TRK-OLD")
                .build();
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW)))
                .thenThrow(new DataIntegrityViolationException("uk_shipment_creation_task_order"));
        when(writer.findByOrderId(ORDER_ID)).thenReturn(completed);
        when(shipmentRepository.existsByOrderId(ORDER_ID)).thenReturn(true);

        service.createForOrder(ORDER_ID, adapter);

        verify(writer, never()).reopen(any(), any(), any());
        verify(adapter, never()).createShipment(any());
    }

    @Test
    void createForOrder_claimsTheOrderBeforeTheCarrierIsCalled() {
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW))).thenReturn(task(0));
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(adapter.createShipment(ORDER_ID))
                .thenReturn(new CarrierAdapter.ShipmentDraft("TRK-1", ShipmentStatus.CREATED));

        service.createForOrder(ORDER_ID, adapter);

        verify(writer).claim(ORDER_ID, Carrier.MANUAL, NOW);
        verify(adapter).createShipment(ORDER_ID);
        verify(writer).complete(eq(TASK_ID), any(Shipment.class));
    }

    @Test
    void successfulCreation_persistsTheTrackingNumberAndCompletesTheTask() {
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW))).thenReturn(task(0));
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(adapter.createShipment(ORDER_ID))
                .thenReturn(new CarrierAdapter.ShipmentDraft("TRK-1", ShipmentStatus.CREATED));

        service.createForOrder(ORDER_ID, adapter);

        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(writer).complete(eq(TASK_ID), captor.capture());
        Shipment created = captor.getValue();
        assertThat(created.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(created.getCarrier()).isEqualTo(Carrier.MANUAL);
        assertThat(created.getTrackingNumber()).isEqualTo("TRK-1");
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(created.isAutoDelivered()).isFalse();
        assertThat(meterRegistry.counter("shipping.shipment.created.count", "carrier", "MANUAL").count())
                .isEqualTo(1.0);
    }

    @Test
    void carrierFailure_isRecordedAndRescheduledInsteadOfBeingLost() {
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW))).thenReturn(task(0));
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(adapter.createShipment(ORDER_ID)).thenThrow(BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, "MANUAL"));

        assertThatCode(() -> service.createForOrder(ORDER_ID, adapter)).doesNotThrowAnyException();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(writer).reschedule(eq(TASK_ID), eq(1), any(Instant.class), error.capture());
        assertThat(error.getValue()).isNotBlank();
        verify(writer, never()).failPermanently(any(), anyInt(), anyString());
        assertThat(meterRegistry.counter("shipping.shipment.creation.failed.count", "carrier", "MANUAL").count())
                .isEqualTo(1.0);
    }

    @Test
    void carrierFailureOnTheLastAttempt_parksTheTaskAsPermanentlyFailed() {
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(writer.claim(eq(ORDER_ID), eq(Carrier.MANUAL), eq(NOW))).thenReturn(task(4));
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(adapter.createShipment(ORDER_ID)).thenThrow(BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, "MANUAL"));

        assertThatCode(() -> service.createForOrder(ORDER_ID, adapter)).doesNotThrowAnyException();

        verify(writer).failPermanently(eq(TASK_ID), eq(5), anyString());
        verify(writer, never()).reschedule(any(), anyInt(), any(), anyString());
        assertThat(meterRegistry.counter("shipping.shipment.creation.failed.permanent.count",
                "carrier", "MANUAL").count()).isEqualTo(1.0);
    }

    @Test
    void retryAfterAFailure_findsTheExistingShipmentAndNeverCallsTheCarrierAgain() {
        Shipment existing = Shipment.builder()
                .id(SHIPMENT_ID)
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .trackingNumber("TRK-9")
                .status(ShipmentStatus.CREATED)
                .build();
        when(shipmentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        service.attempt(task(1), adapter);

        verify(adapter, never()).createShipment(any());
        verify(writer).completeWithExisting(TASK_ID, SHIPMENT_ID, "TRK-9");
    }

    @Test
    void backoffGrowsExponentiallyAndIsBoundedByTheAttemptNumber() {
        assertThat(ShipmentCreationService.backoffSeconds(1)).isEqualTo(60L);
        assertThat(ShipmentCreationService.backoffSeconds(2)).isEqualTo(300L);
        assertThat(ShipmentCreationService.backoffSeconds(3)).isEqualTo(1500L);
    }
}
