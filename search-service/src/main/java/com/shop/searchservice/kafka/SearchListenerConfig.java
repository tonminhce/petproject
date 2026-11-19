package com.shop.searchservice.kafka;

import com.shop.common.kafka.config.KafkaProperties;
import com.shop.common.kafka.consumer.BaseKafkaListenerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Listener container factory for the product lifecycle topic (search spec D1).
 * Uses {@link BaseKafkaListenerConfig} for raw-string deserialization, W3C trace
 * extraction, retry error handling, and optional DLT recovery.
 */
@EnableKafka
@Configuration
public class SearchListenerConfig extends BaseKafkaListenerConfig<String> {

    public SearchListenerConfig(KafkaProperties kafkaProperties,
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
        super(String.class, kafkaProperties, kafkaTemplateProvider.getIfAvailable());
    }

    @Override
    @Bean(name = "searchListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
        return kafkaListenerContainerFactory();
    }
}
