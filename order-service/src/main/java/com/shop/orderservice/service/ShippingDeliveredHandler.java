package com.shop.orderservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.dto.ShippingDeliveredEvent;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShippingDeliveredHandler {

    static final String EVENT_TYPE_DELIVERED = "shipping.delivered.v1";

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final OrderEventPublisher orderEventPublisher;

    @Transactional
    public void handle(ShippingDeliveredEvent event) {
        if (!EVENT_TYPE_DELIVERED.equals(event.getEventType())) {
            log.info("Ignoring shipping event type {} for order {}", event.getEventType(), event.getOrderId());
            return;
        }
        Order order = orderRepository.findById(event.getOrderId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, event.getOrderId()));
        if (order.getStatus() != OrderStatus.SHIPPED) {
            log.info("Shipping delivered event for order {} in status {} — no-op", event.getOrderId(), order.getStatus());
            return;
        }
        orderStatusService.validateTransition(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(Instant.now());
        orderRepository.save(order);
        orderEventPublisher.publishStatusChanged(order);
    }
}
