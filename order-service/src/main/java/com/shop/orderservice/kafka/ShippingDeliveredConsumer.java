package com.shop.orderservice.kafka;

import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.orderservice.dto.ShippingDeliveredEvent;
import com.shop.orderservice.service.ShippingDeliveredHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class ShippingDeliveredConsumer extends BaseKafkaConsumer<String, ShippingDeliveredEvent> {

    private final ShippingDeliveredHandler handler;

    public ShippingDeliveredConsumer(ShippingDeliveredHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "shop.shipping.lifecycle.v1", containerFactory = "shippingListenerFactory")
    public void onMessage(ShippingDeliveredEvent event, MessageHeaders headers) {
        processMessage(event, headers, this::handleContained);
    }

    private void handleContained(ShippingDeliveredEvent event) {
        try {
            handler.handle(event);
        } catch (Exception ex) {
            log.error("Failed to process shipping event {} for order {}", event.getEventId(), event.getOrderId(), ex);
        }
    }
}
