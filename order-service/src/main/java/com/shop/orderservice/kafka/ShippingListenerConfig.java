package com.shop.orderservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import com.shop.orderservice.dto.ShippingDeliveredEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableKafka
@Configuration
public class ShippingListenerConfig extends BaseKafkaListenerConfig<String> {

    public ShippingListenerConfig(KafkaProperties kafkaProperties,
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        super(String.class, kafkaProperties, kafkaTemplate);
    }

    @Override
    @Bean(name = "shippingListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
