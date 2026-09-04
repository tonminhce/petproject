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
    public void onMessage(String rawValue, MessageHeaders headers) {
        // H-1 raw-wire entry: the base unwraps-once + binds the typed event;
        // a decode failure is a contained ack-skip inside the base.
        processMessage(rawValue, headers, ShippingDeliveredEvent.class, handler::handle);
    }
}
