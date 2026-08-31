package com.shop.shippingservice.service;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.entity.ShipmentEvent;
import com.shop.shippingservice.outbox.ShippingEventPublisher;
import com.shop.shippingservice.repository.ShipmentEventRepository;
import com.shop.shippingservice.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookEventWriterTest {

    private static final UUID SHIPMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentEventRepository eventRepository;
    @Mock ShippingEventPublisher publisher;

    private WebhookEventWriter writer;

    @BeforeEach
    void setUp() {
        writer = new WebhookEventWriter(shipmentRepository, eventRepository, publisher);
    }

    private Shipment shipment() {
        return Shipment.builder()
                .id(SHIPMENT_ID)
                .orderId(UUID.randomUUID())
                .carrier(Carrier.GHN)
                .status(ShipmentStatus.DELIVERED)
                .build();
    }

    private ShipmentEvent event() {
        return ShipmentEvent.builder()
                .id(UUID.randomUUID())
                .carrier(Carrier.GHN)
                .providerEventId("evt_123")
                .type("carrier.event.v1")
                .payload("{}")
                .status("FAILED")
                .build();
    }

    @Test
    void insert_flushesEventRow() {
        ShipmentEvent inserted = event();
        when(eventRepository.saveAndFlush(inserted)).thenReturn(inserted);

        ShipmentEvent result = writer.insert(inserted);

        assertThat(result).isSameAs(inserted);
        verify(eventRepository).saveAndFlush(inserted);
    }

    @Test
    void complete_delivered_savesBothAndPublishesOutboxRow() {
        Shipment shipment = shipment();
        ShipmentEvent event = event();

        writer.complete(shipment, event, true);

        verify(shipmentRepository).save(shipment);
        verify(eventRepository).save(event);
        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(publisher).publishDelivered(captor.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(captor.getValue()).isSameAs(shipment);
    }

    @Test
    void complete_notDelivered_savesBothWithoutPublishing() {
        Shipment shipment = shipment();
        ShipmentEvent event = event();

        writer.complete(shipment, event, false);

        verify(shipmentRepository).save(shipment);
        verify(eventRepository).save(event);
        verify(publisher, never()).publishDelivered(any(), anyBoolean());
    }
}
