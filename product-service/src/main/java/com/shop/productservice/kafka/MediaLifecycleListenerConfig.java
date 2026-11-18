package com.shop.productservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

@EnableKafka
@Configuration
public class MediaLifecycleListenerConfig {

    @Bean(name = "mediaListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> mediaListenerFactory(
            KafkaProperties kafkaProperties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        ConsumerFactory<String, String> consumerFactory =
            new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
