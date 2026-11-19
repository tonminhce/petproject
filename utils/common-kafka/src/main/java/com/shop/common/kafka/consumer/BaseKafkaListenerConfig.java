package com.shop.common.kafka.consumer;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

public abstract class BaseKafkaListenerConfig<K> {

    private final Class<K> keyType;
    private final KafkaProperties kafkaProperties;
    private final KafkaOperations<?, ?> kafkaTemplate;

    protected BaseKafkaListenerConfig(Class<K> keyType, KafkaProperties kafkaProperties, KafkaOperations<?, ?> kafkaTemplate) {
        this.keyType = keyType;
        this.kafkaProperties = kafkaProperties;
        this.kafkaTemplate = kafkaTemplate;
    }

    protected BaseKafkaListenerConfig(Class<K> keyType, KafkaProperties kafkaProperties) {
        this(keyType, kafkaProperties, null);
    }

    public abstract ConcurrentKafkaListenerContainerFactory<K, String> listenerContainerFactory();

    protected ConcurrentKafkaListenerContainerFactory<K, String> kafkaListenerContainerFactory() {
        return kafkaListenerContainerFactory(this.kafkaTemplate);
    }

    protected ConcurrentKafkaListenerContainerFactory<K, String> kafkaListenerContainerFactory(KafkaOperations<?, ?> template) {
        var factory = new ConcurrentKafkaListenerContainerFactory<K, String>();
        factory.setConsumerFactory(rawStringConsumerFactory());
        factory.getContainerProperties().setObservationEnabled(true);
        if (template != null) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
            factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));
        } else {
            factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
        }
        return factory;
    }

    private ConsumerFactory<K, String> rawStringConsumerFactory() {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        return new DefaultKafkaConsumerFactory<>(
                props,
                BaseKafkaListenerConfig::keyDeserializer,
                BaseKafkaListenerConfig::valueDeserializer);
    }

    @SuppressWarnings("unchecked")
    private static <K> Deserializer<K> keyDeserializer() {
        return (Deserializer<K>) new StringDeserializer();
    }

    @SuppressWarnings("unchecked")
    private static <V> Deserializer<V> valueDeserializer() {
        return (Deserializer<V>) new StringDeserializer();
    }
}
