package com.shop.shippingservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
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

    @KafkaListener(topics = KafkaTopicContract.SHOP_ORDER_LIFECYCLE, groupId = "shipping-service",
            containerFactory = "shippingListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {

        processMessage(rawValue, headers, OrderLifecycleEvent.class, shipmentService::handleOrderEvent);
    }
}
