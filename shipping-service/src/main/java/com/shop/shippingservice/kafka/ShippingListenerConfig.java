package com.shop.shippingservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import com.shop.shippingservice.dto.OrderLifecycleEvent;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableKafka
@Configuration
public class ShippingListenerConfig extends BaseKafkaListenerConfig<String, OrderLifecycleEvent> {

    public ShippingListenerConfig(KafkaProperties kafkaProperties) {
        super(String.class, OrderLifecycleEvent.class, kafkaProperties);
    }

    @Override
    @Bean(name = "shippingListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, OrderLifecycleEvent> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
