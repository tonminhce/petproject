package com.shop.searchservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@EnableKafka
@Configuration
public class SearchListenerConfig extends BaseKafkaListenerConfig<String, ProductLifecycleEvent> {

    public SearchListenerConfig(KafkaProperties kafkaProperties) {
        super(String.class, ProductLifecycleEvent.class, kafkaProperties);
    }

    @Override
    @Bean(name = "searchListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ProductLifecycleEvent> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
