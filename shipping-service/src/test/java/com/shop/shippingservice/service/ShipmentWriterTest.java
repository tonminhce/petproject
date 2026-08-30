package com.shop.shippingservice.service;

import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.outbox.ShippingEventPublisher;
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
class ShipmentWriterTest {

    private static final UUID SHIPMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock ShipmentRepository repository;
    @Mock ShippingEventPublisher publisher;

    private ShipmentWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ShipmentWriter(repository, publisher);
    }

    private Shipment shipment() {
        return Shipment.builder()
                .id(SHIPMENT_ID)
                .orderId(UUID.randomUUID())
                .carrier(Carrier.MANUAL)
                .status(ShipmentStatus.DELIVERED)
                .build();
    }

    @Test
    void insert_flushesShipmentRow() {
        Shipment shipment = shipment();
        when(repository.saveAndFlush(shipment)).thenReturn(shipment);

        Shipment result = writer.insert(shipment);

        assertThat(result).isSameAs(shipment);
        verify(repository).saveAndFlush(shipment);
    }

    @Test
    void save_persistsWithoutPublishing() {
        Shipment shipment = shipment();
        when(repository.save(shipment)).thenReturn(shipment);

        Shipment result = writer.save(shipment);

        assertThat(result).isSameAs(shipment);
        verify(repository).save(shipment);
        verify(publisher, never()).publishDelivered(any(), anyBoolean());
    }

    @Test
    void saveDelivered_savesAndPublishesOutboxRowInMethod() {
        Shipment shipment = shipment();
        when(repository.save(shipment)).thenReturn(shipment);

        Shipment result = writer.saveDelivered(shipment, true);

        assertThat(result).isSameAs(shipment);
        ArgumentCaptor<Shipment> captor = ArgumentCaptor.forClass(Shipment.class);
        verify(publisher).publishDelivered(captor.capture(), org.mockito.ArgumentMatchers.eq(true));
        assertThat(captor.getValue()).isSameAs(shipment);
    }
}
