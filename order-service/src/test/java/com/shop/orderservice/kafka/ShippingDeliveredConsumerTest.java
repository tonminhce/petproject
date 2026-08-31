package com.shop.orderservice.kafka;

import com.shop.orderservice.dto.ShippingDeliveredEvent;
import com.shop.orderservice.service.ShippingDeliveredHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ShippingDeliveredConsumerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock ShippingDeliveredHandler handler;

    private ShippingDeliveredConsumer consumer;

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

    @Test
    void onMessage_delegatesEventToHandler() {
        ShippingDeliveredEvent event = deliveredEvent();

        consumer.onMessage(event, new MessageHeaders(new HashMap<>()));

        verify(handler).handle(event);
    }

    @Test
    void onMessage_handlerThrows_exceptionContainedNeverEscapesListener() {
        ShippingDeliveredEvent event = deliveredEvent();
        doThrow(new RuntimeException("db down")).when(handler).handle(any());

        assertThatCode(() -> consumer.onMessage(event, new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(handler).handle(event);
    }
}
