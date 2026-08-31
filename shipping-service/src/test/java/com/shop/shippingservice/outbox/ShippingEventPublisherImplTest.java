package com.shop.shippingservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ShippingEventPublisherImplTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHIPMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock OutboxEventRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ShippingEventPublisherImpl publisher;

    @BeforeEach
    void setUp() {
        publisher = new ShippingEventPublisherImpl(outboxRepository, objectMapper);
    }

    private Shipment shipment() {
        return Shipment.builder()
                .id(SHIPMENT_ID)
                .orderId(ORDER_ID)
                .carrier(Carrier.GHN)
                .trackingNumber("TRK-1")
                .status(ShipmentStatus.DELIVERED)
                .build();
    }

    @Test
    void publishDelivered_savesPendingOutboxRowWithEnvelopeAndPayload() throws Exception {
        publisher.publishDelivered(shipment(), true);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getEventId()).isNotBlank();
        assertThat(UUID.fromString(event.getEventId())).isNotNull();
        assertThat(event.getAggregateType()).isEqualTo("Shipment");
        assertThat(event.getAggregateId()).isEqualTo(SHIPMENT_ID);
        assertThat(event.getEventType()).isEqualTo("shipping.delivered.v1");
        assertThat(event.getTopic()).isEqualTo("shop.shipping.lifecycle.v1");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(payload.get("eventType").asText()).isEqualTo("shipping.delivered.v1");
        assertThat(payload.get("occurredAt").asText()).isNotBlank();
        assertThat(payload.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(payload.get("shipmentId").asText()).isEqualTo(SHIPMENT_ID.toString());
        assertThat(payload.get("carrier").asText()).isEqualTo("GHN");
        assertThat(payload.get("trackingNumber").asText()).isEqualTo("TRK-1");
        assertThat(payload.get("autoDelivered").asBoolean()).isTrue();
    }

    @Test
    void publishDelivered_freshEventIdPerCall() {
        publisher.publishDelivered(shipment(), false);
        publisher.publishDelivered(shipment(), false);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getEventId())
                .isNotEqualTo(captor.getAllValues().get(1).getEventId());
    }
}
