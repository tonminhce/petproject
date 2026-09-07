package com.shop.orderservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@EnableKafka
@Configuration
public class PaymentListenerConfig extends BaseKafkaListenerConfig<String> {

    public PaymentListenerConfig(KafkaProperties kafkaProperties,
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        super(String.class, kafkaProperties, kafkaTemplate);
    }

    @Override
    @Bean(name = "paymentListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
