package com.shop.orderservice.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.orderservice.dto.ShippingDeliveredEvent;
import com.shop.orderservice.service.ShippingDeliveredHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ShippingDeliveredConsumerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock ShippingDeliveredHandler handler;

    private ShippingDeliveredConsumer consumer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new ShippingDeliveredConsumer(handler);
    }

    private ShippingDeliveredEvent deliveredEvent() {
        ShippingDeliveredEvent e = new ShippingDeliveredEvent();
        e.setEventId("44444444-4444-4444-4444-444444444444");
        e.setEventType("shipping.delivered.v1");
        e.setOccurredAt("2026-08-31T10:00:00Z");
        e.setOrderId(ORDER_ID);
        e.setAutoDelivered(false);
        return e;
    }

    /** The LEGACY pre-R1 wire: payload String JSON-string-encoded by JsonKafkaSerializer (H-1 tolerance). */
    private String doubleEncodedToken(ShippingDeliveredEvent event) throws JsonProcessingException {
        return objectMapper.writeValueAsString(objectMapper.writeValueAsString(event));
    }

    @Test
    @DisplayName("H-1: the legacy double-encoded (pre-R1) fleet wire token binds to the typed handler")
    void onMessage_doubleEncodedToken_bindsTypedEventAndDelegates() throws JsonProcessingException {
        ShippingDeliveredEvent event = deliveredEvent();

        consumer.onMessage(doubleEncodedToken(event), new MessageHeaders(new HashMap<>()));

        ArgumentCaptor<ShippingDeliveredEvent> captor = ArgumentCaptor.forClass(ShippingDeliveredEvent.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().getEventType()).isEqualTo("shipping.delivered.v1");
    }

    @Test
    @DisplayName("H-1 tolerance: a future single-encoded relay payload also binds")
    void onMessage_singleEncodedJson_alsoAccepted() throws JsonProcessingException {
        ShippingDeliveredEvent event = deliveredEvent();

        consumer.onMessage(objectMapper.writeValueAsString(event), new MessageHeaders(new HashMap<>()));

        ArgumentCaptor<ShippingDeliveredEvent> captor = ArgumentCaptor.forClass(ShippingDeliveredEvent.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("H-1 containment: poison payload is an ack-skip — handler never invoked, no throw")
    void onMessage_poisonPayload_containedAckSkip() {
        assertThatCode(() -> consumer.onMessage("{ this is not json }", new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(handler, never()).handle(any());
    }

    @Test
    void onMessage_handlerThrows_propagatesForRetryAndDlt() throws JsonProcessingException {
        doThrow(new RuntimeException("db down")).when(handler).handle(any());

        assertThatThrownBy(() -> consumer.onMessage(doubleEncodedToken(deliveredEvent()), new MessageHeaders(new HashMap<>())))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("db down");

        verify(handler).handle(any());
    }
}
