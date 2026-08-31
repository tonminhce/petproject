package com.shop.orderservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import com.shop.orderservice.dto.ShippingDeliveredEvent;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableKafka
@Configuration
public class ShippingListenerConfig extends BaseKafkaListenerConfig<String, ShippingDeliveredEvent> {

    public ShippingListenerConfig(KafkaProperties kafkaProperties) {
        super(String.class, ShippingDeliveredEvent.class, kafkaProperties);
    }

    @Override
    @Bean(name = "shippingListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ShippingDeliveredEvent> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
