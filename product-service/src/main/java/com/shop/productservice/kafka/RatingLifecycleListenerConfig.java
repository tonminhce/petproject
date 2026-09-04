package com.shop.productservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableKafka
@Configuration
public class RatingLifecycleListenerConfig extends BaseKafkaListenerConfig<String> {

    public RatingLifecycleListenerConfig(KafkaProperties kafkaProperties,
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        super(String.class, kafkaProperties, kafkaTemplate);
    }

    @Override
    @Bean(name = "ratingListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
