package com.shop.common.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.serialization.JsonKafkaSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Auto-wires the shared {@link KafkaTemplate} and {@link KafkaMessagePublisher}.
 *
 * <p>Activated whenever {@code spring-kafka} is on the classpath and
 * {@code shop.kafka.enabled} is not explicitly set to {@code false}.</p>
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "shop.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, Object> kafkaProducerFactory(KafkaProperties kafkaProperties,
                                                                ObjectProvider<ObjectMapper> objectMapper) {
        JsonKafkaSerializer<Object> serializer = new JsonKafkaSerializer<>(objectMapper.getIfAvailable(ObjectMapper::new));
        var props = new java.util.HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        props.put("value.serializer", serializer.getClass().getName());
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaMessagePublisher kafkaMessagePublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaMessagePublisher(kafkaTemplate);
    }
}
