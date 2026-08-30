package com.shop.shippingservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.shippingservice.carrier.CarrierAdapter;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.dto.OrderLifecycleEvent;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.service.ShipmentWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SHIPMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Mock ShipmentRepository repository;
    @Mock ShipmentWriter writer;
    @Mock CarrierAdapter adapter;
    @Mock Clock clock;

    private ShipmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentServiceImpl(repository, writer, adapter, clock);
    }

    private OrderLifecycleEvent event(String status) {
        OrderLifecycleEvent e = new OrderLifecycleEvent();
        e.setEventId(EVENT_ID.toString());
        e.setEventType("order.lifecycle.v1");
        e.setOccurredAt("2026-08-30T10:00:00Z");
        e.setOrderId(ORDER_ID);
        e.setStatus(status);
        return e;
    }

    @Test
    void confirmedEvent_insertsCreatedShipmentRow() {
        when(repository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(adapter.createShipment(ORDER_ID))
                .thenReturn(new CarrierAdapter.ShipmentDraft(null, ShipmentStatus.CREATED));

        service.handleOrderEvent(event("CONFIRMED"));

        verify(adapter).createShipment(ORDER_ID);
        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(writer).insert(captor.capture());
        Shipment inserted = captor.getValue();
        assertThat(inserted.getId()).isNotNull();
        assertThat(inserted.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inserted.getCarrier()).isEqualTo(Carrier.MANUAL);
        assertThat(inserted.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(inserted.getTrackingNumber()).isNull();
        assertThat(inserted.isAutoDelivered()).isFalse();
    }

    @Test
    void confirmedEvent_existingShipmentForOrder_skipsAdapterAndInsert() {
        when(repository.existsByOrderId(ORDER_ID)).thenReturn(true);

        service.handleOrderEvent(event("CONFIRMED"));

        verify(adapter, never()).createShipment(any());
        verify(writer, never()).insert(any());
    }

    @Test
    void confirmedEvent_duplicateInsertRace_noCrash() {
        when(repository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(adapter.carrier()).thenReturn(Carrier.MANUAL);
        when(adapter.createShipment(ORDER_ID))
                .thenReturn(new CarrierAdapter.ShipmentDraft(null, ShipmentStatus.CREATED));
        when(writer.insert(any(Shipment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_shipments_order_id"));

        assertThatCode(() -> service.handleOrderEvent(event("CONFIRMED")))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledEvent_createdShipment_transitionsToCancelled() {
        Shipment shipment = Shipment.builder()
                .id(UUID.randomUUID())
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .status(ShipmentStatus.CREATED)
                .build();
        when(repository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(shipment));

        service.handleOrderEvent(event("CANCELLED"));

        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(writer).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(captor.getValue().getPreviousStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void cancelledEvent_inFlightShipment_untouched() {
        Shipment shipment = Shipment.builder()
                .id(UUID.randomUUID())
                .orderId(ORDER_ID)
                .carrier(Carrier.MANUAL)
                .status(ShipmentStatus.IN_TRANSIT)
                .build();
        when(repository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(shipment));

        service.handleOrderEvent(event("CANCELLED"));

        verify(writer, never()).save(any());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
    }

    @Test
    void deliveredEvent_noop() {
        service.handleOrderEvent(event("DELIVERED"));

        verifyNoInteractions(repository, writer, adapter);
    }

    private Shipment shipment(Carrier carrier, ShipmentStatus status) {
        return Shipment.builder()
                .id(SHIPMENT_ID)
                .orderId(ORDER_ID)
                .carrier(carrier)
                .status(status)
                .build();
    }

    // --- backoffice: assignTracking ---

    @Test
    void assignTracking_createdManualShipment_setsTrackingAdvancesAndStampsUpdate() {
        Shipment shipment = shipment(Carrier.MANUAL, ShipmentStatus.CREATED);
        when(repository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(clock.instant()).thenReturn(NOW);
        when(writer.save(shipment)).thenReturn(shipment);

        var response = service.assignTracking(SHIPMENT_ID, "  TRK-42  ");

        assertThat(response.status()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(response.trackingNumber()).isEqualTo("TRK-42");
        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(writer).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(captor.getValue().getPreviousStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(captor.getValue().getTrackingNumber()).isEqualTo("TRK-42");
        assertThat(captor.getValue().getLastCarrierUpdate()).isEqualTo(NOW);
    }

    @Test
    void assignTracking_blankTracking_throwsTrackingRequired() {
        assertThatThrownBy(() -> service.assignTracking(SHIPMENT_ID, "   "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10005");

        verifyNoInteractions(repository, writer);
    }

    @Test
    void assignTracking_unknownShipment_throwsNotFound() {
        when(repository.findById(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignTracking(SHIPMENT_ID, "TRK-42"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10001");
    }

    @Test
    void assignTracking_nonCreatedStatus_throwsInvalidTransition() {
        when(repository.findById(SHIPMENT_ID))
                .thenReturn(Optional.of(shipment(Carrier.MANUAL, ShipmentStatus.IN_TRANSIT)));

        assertThatThrownBy(() -> service.assignTracking(SHIPMENT_ID, "TRK-42"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10003");

        verify(writer, never()).save(any());
    }

    @Test
    void assignTracking_nonManualCarrier_throwsInvalidTransition() {
        when(repository.findById(SHIPMENT_ID))
                .thenReturn(Optional.of(shipment(Carrier.NOOP, ShipmentStatus.CREATED)));

        assertThatThrownBy(() -> service.assignTracking(SHIPMENT_ID, "TRK-42"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10003");

        verify(writer, never()).save(any());
    }

    // --- backoffice: transition / fail / retry ---

    @Test
    void transition_legalTarget_advancesViaFsm() {
        Shipment shipment = shipment(Carrier.MANUAL, ShipmentStatus.PICKED_UP);
        when(repository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(writer.save(shipment)).thenReturn(shipment);

        var response = service.transition(SHIPMENT_ID, ShipmentStatus.IN_TRANSIT);

        assertThat(response.status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(response.previousStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void transition_illegalTarget_throwsInvalidTransition() {
        when(repository.findById(SHIPMENT_ID))
                .thenReturn(Optional.of(shipment(Carrier.MANUAL, ShipmentStatus.CREATED)));

        assertThatThrownBy(() -> service.transition(SHIPMENT_ID, ShipmentStatus.DELIVERED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10003");
    }

    @Test
    void fail_inFlightShipment_advancesToDeliveryFailed() {
        Shipment shipment = shipment(Carrier.MANUAL, ShipmentStatus.OUT_FOR_DELIVERY);
        when(repository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(writer.save(shipment)).thenReturn(shipment);

        var response = service.fail(SHIPMENT_ID);

        assertThat(response.status()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
        assertThat(response.previousStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void fail_notInFlight_throwsInvalidTransition() {
        when(repository.findById(SHIPMENT_ID))
                .thenReturn(Optional.of(shipment(Carrier.MANUAL, ShipmentStatus.DELIVERED)));

        assertThatThrownBy(() -> service.fail(SHIPMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10003");
    }

    @Test
    void retry_failedShipment_advancesToInTransit() {
        Shipment shipment = shipment(Carrier.MANUAL, ShipmentStatus.DELIVERY_FAILED);
        when(repository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(writer.save(shipment)).thenReturn(shipment);

        var response = service.retry(SHIPMENT_ID);

        assertThat(response.status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(response.previousStatus()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
    }

    @Test
    void retry_notFailed_throwsInvalidTransition() {
        when(repository.findById(SHIPMENT_ID))
                .thenReturn(Optional.of(shipment(Carrier.MANUAL, ShipmentStatus.CREATED)));

        assertThatThrownBy(() -> service.retry(SHIPMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10003");
    }

    // --- backoffice: findAll filter routing ---

    @Test
    void findAll_unfiltered_usesNewestFirstFinder() {
        when(repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(shipment(Carrier.MANUAL, ShipmentStatus.CREATED)),
                        PageRequest.of(0, 10), 1));

        var page = service.findAll(null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(SHIPMENT_ID);
        verify(repository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));
    }

    @Test
    void findAll_statusFilter_usesStatusFinder() {
        when(repository.findAllByStatusOrderByCreatedAtDesc(ShipmentStatus.IN_TRANSIT, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.findAll(ShipmentStatus.IN_TRANSIT, null, null, PageRequest.of(0, 10));

        verify(repository).findAllByStatusOrderByCreatedAtDesc(ShipmentStatus.IN_TRANSIT, PageRequest.of(0, 10));
    }

    @Test
    void findAll_carrierFilter_usesCarrierFinder() {
        when(repository.findAllByCarrierOrderByCreatedAtDesc(Carrier.MANUAL, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.findAll(null, Carrier.MANUAL, null, PageRequest.of(0, 10));

        verify(repository).findAllByCarrierOrderByCreatedAtDesc(Carrier.MANUAL, PageRequest.of(0, 10));
    }

    @Test
    void findAll_orderIdFilter_mapsOptionalToSingleElementOrEmptyPage() {
        when(repository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(shipment(Carrier.MANUAL, ShipmentStatus.CREATED)));

        var found = service.findAll(null, null, ORDER_ID, PageRequest.of(0, 20));
        assertThat(found.getTotalElements()).isEqualTo(1);
        assertThat(found.getContent()).hasSize(1);
        assertThat(found.getContent().get(0).orderId()).isEqualTo(ORDER_ID);

        when(repository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        var empty = service.findAll(null, null, ORDER_ID, PageRequest.of(2, 20));
        assertThat(empty.getTotalElements()).isZero();
        assertThat(empty.getContent()).isEmpty();
    }

    @Test
    void findAll_unknownShipmentId_throwsNotFound() {
        when(repository.findById(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(SHIPMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("SHP-10001");
    }
}
