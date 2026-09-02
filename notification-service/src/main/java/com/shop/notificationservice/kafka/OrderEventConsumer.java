package com.shop.notificationservice.kafka;

import com.shop.common.kafka.consumer.BaseKafkaConsumer;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer extends BaseKafkaConsumer<String, OrderLifecycleEvent> {

    private final NotificationService notificationService;

    public OrderEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "shop.order.lifecycle.v1", containerFactory = "notificationListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {
        // H-1 raw-wire entry: the base unwraps-once + binds the typed event;
        // a decode failure is a contained ack-skip inside the base.
        processMessage(rawValue, headers, OrderLifecycleEvent.class, notificationService::handle);
    }
}
