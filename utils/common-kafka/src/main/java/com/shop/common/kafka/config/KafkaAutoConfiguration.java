package com.shop.common.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.replay.DeadLetterReplayEndpoint;
import com.shop.common.kafka.replay.DeadLetterReplayService;
import com.shop.common.kafka.serialization.JsonKafkaSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;

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
        var props = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        props.put("value.serializer", serializer.getClass().getName());
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean(name = "stringKafkaTemplate")
    public KafkaTemplate<String, String> stringKafkaTemplate(KafkaProperties kafkaProperties) {
        var props = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterReplayService deadLetterReplayService(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> stringKafkaTemplate,
            KafkaProperties kafkaProperties) {
        return new DeadLetterReplayService(stringKafkaTemplate,
                kafkaProperties.getBootstrapServers(), "shop");
    }

    @Bean
    @ConditionalOnClass(org.springframework.boot.actuate.endpoint.annotation.Endpoint.class)
    @ConditionalOnProperty(prefix = "shop.kafka.dlq-replay", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DeadLetterReplayEndpoint deadLetterReplayEndpoint(DeadLetterReplayService replayService) {
        return new DeadLetterReplayEndpoint(replayService);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaMessagePublisher kafkaMessagePublisher(
            @Qualifier("stringKafkaTemplate")
            KafkaTemplate<String, String> stringKafkaTemplate) {
        return new KafkaMessagePublisher(stringKafkaTemplate);
    }
}
