package com.shop.shippingservice.kafka;

import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.shippingservice.dto.OrderLifecycleEvent;
import com.shop.shippingservice.service.ShipmentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer extends BaseKafkaConsumer<String, OrderLifecycleEvent> {

    private final ShipmentService shipmentService;

    public OrderEventConsumer(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @KafkaListener(topics = "shop.order.lifecycle.v1", containerFactory = "shippingListenerFactory")
    public void onMessage(OrderLifecycleEvent event, MessageHeaders headers) {
        processMessage(event, headers, shipmentService::handleOrderEvent);
    }
}
