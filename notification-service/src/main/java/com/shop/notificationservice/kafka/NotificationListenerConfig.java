package com.shop.notificationservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationListenerConfig extends BaseKafkaListenerConfig<String, OrderLifecycleEvent> {

    public NotificationListenerConfig(KafkaProperties kafkaProperties) {
        super(String.class, OrderLifecycleEvent.class, kafkaProperties);
    }

    @Override
    @Bean(name = "notificationListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, OrderLifecycleEvent> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
