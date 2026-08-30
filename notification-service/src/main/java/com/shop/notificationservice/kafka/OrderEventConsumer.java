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
    public void onMessage(OrderLifecycleEvent event, MessageHeaders headers) {
        processMessage(event, headers, notificationService::handle);
    }
}
