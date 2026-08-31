package com.shop.orderservice.service;

import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.ShippingDeliveredEvent;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingDeliveredHandlerTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SHIPMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EVENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock OrderRepository orderRepository;
    @Mock OrderStatusService orderStatusService;
    @Mock OrderEventPublisher orderEventPublisher;

    private ShippingDeliveredHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ShippingDeliveredHandler(orderRepository, orderStatusService, orderEventPublisher);
    }

    private ShippingDeliveredEvent deliveredEvent() {
        ShippingDeliveredEvent e = new ShippingDeliveredEvent();
        e.setEventId(EVENT_ID.toString());
        e.setEventType("shipping.delivered.v1");
        e.setOccurredAt("2026-08-31T10:00:00Z");
        e.setOrderId(ORDER_ID);
        e.setShipmentId(SHIPMENT_ID);
        e.setCarrier("GHN");
        e.setTrackingNumber("TRK-123");
        e.setAutoDelivered(false);
        return e;
    }

    private Order orderWithStatus(OrderStatus status) {
        return Order.builder().id(ORDER_ID).userId(USER_ID).status(status).build();
    }

    @Test
    void shippedOrder_deliveredEvent_transitionsToDeliveredAndPublishes() {
        Order order = orderWithStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        handler.handle(deliveredEvent());

        verify(orderStatusService).validateTransition(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isNotNull();
        verify(orderRepository).save(order);
        verify(orderEventPublisher).publishStatusChanged(order);
    }

    @Test
    void alreadyDeliveredOrder_deliveredEvent_noOp() {
        Order order = orderWithStatus(OrderStatus.DELIVERED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        handler.handle(deliveredEvent());

        verify(orderStatusService, never()).validateTransition(any(), any());
        verify(orderRepository, never()).save(any());
        verify(orderEventPublisher, never()).publishStatusChanged(any());
    }

    @Test
    void confirmedOrder_deliveredEvent_noOp() {
        Order order = orderWithStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        handler.handle(deliveredEvent());

        verify(orderStatusService, never()).validateTransition(any(), any());
        verify(orderRepository, never()).save(any());
        verify(orderEventPublisher, never()).publishStatusChanged(any());
    }

    @Test
    void nonDeliveredEventType_noOpWithoutLoadingOrder() {
        ShippingDeliveredEvent e = deliveredEvent();
        e.setEventType("shipping.cancelled.v1");

        handler.handle(e);

        verifyNoInteractions(orderRepository, orderStatusService, orderEventPublisher);
    }
}
