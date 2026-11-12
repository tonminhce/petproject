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

    // groupId is pinned: it is the consumer's identity, and changing it resets
    // the committed offsets and replays the topic (see BaseKafkaListenerConfig)
    @KafkaListener(topics = "shop.shipping.lifecycle.v1", groupId = "order-service",
            containerFactory = "shippingListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {

        processMessage(rawValue, headers, ShippingDeliveredEvent.class, handler::handle);
    }
}
