package com.shop.notificationservice.kafka;

import com.shop.common.kafka.contract.KafkaTopicContract;
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

    // groupId is pinned: it is the consumer's identity, and changing it resets
    // the committed offsets and replays the topic (see BaseKafkaListenerConfig)
    @KafkaListener(topics = KafkaTopicContract.SHOP_ORDER_LIFECYCLE, groupId = "notification-service",
            containerFactory = "notificationListenerFactory")
    public void onMessage(String rawValue, MessageHeaders headers) {

        processMessage(rawValue, headers, OrderLifecycleEvent.class, notificationService::handle);
    }
}
