package com.shop.shippingservice.service.impls;

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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock ShipmentRepository repository;
    @Mock ShipmentWriter writer;
    @Mock CarrierAdapter adapter;

    private ShipmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentServiceImpl(repository, writer, adapter);
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
}
