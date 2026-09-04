package com.shop.notificationservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableKafka
@Configuration
public class NotificationListenerConfig extends BaseKafkaListenerConfig<String> {

    public NotificationListenerConfig(KafkaProperties kafkaProperties,
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        super(String.class, kafkaProperties, kafkaTemplate);
    }

    @Override
    @Bean(name = "notificationListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
