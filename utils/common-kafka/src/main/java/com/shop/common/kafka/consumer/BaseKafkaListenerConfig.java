package com.shop.common.kafka.consumer;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseKafkaListenerConfig<K> implements BeanFactoryAware {

    private static final List<String> TEMPLATE_BEAN_NAMES = List.of("stringKafkaTemplate", "kafkaTemplate");

    private final Class<K> keyType;
    private final KafkaProperties kafkaProperties;
    private final KafkaOperations<?, ?> kafkaTemplate;
    private BeanFactory beanFactory;

    protected BaseKafkaListenerConfig(Class<K> keyType, KafkaProperties kafkaProperties, KafkaOperations<?, ?> kafkaTemplate) {
        this.keyType = keyType;
        this.kafkaProperties = kafkaProperties;
        this.kafkaTemplate = kafkaTemplate;
    }

    protected BaseKafkaListenerConfig(Class<K> keyType, KafkaProperties kafkaProperties) {
        this(keyType, kafkaProperties, null);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public abstract ConcurrentKafkaListenerContainerFactory<K, String> listenerContainerFactory();

    protected ConcurrentKafkaListenerContainerFactory<K, String> kafkaListenerContainerFactory() {
        return kafkaListenerContainerFactory(this.kafkaTemplate);
    }

    protected ConcurrentKafkaListenerContainerFactory<K, String> kafkaListenerContainerFactory(KafkaOperations<?, ?> template) {
        var factory = new ConcurrentKafkaListenerContainerFactory<K, String>();
        factory.setConsumerFactory(rawStringConsumerFactory());
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(deadLetterErrorHandler(template));
        return factory;
    }

    private DefaultErrorHandler deadLetterErrorHandler(KafkaOperations<?, ?> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterPublisher(template));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    private KafkaOperations<?, ?> deadLetterPublisher(KafkaOperations<?, ?> template) {
        if (template != null) {
            return template;
        }
        KafkaOperations<?, ?> fromContext = templateFromContext();
        if (fromContext != null) {
            return fromContext;
        }
        return ownDeadLetterTemplate();
    }

    private KafkaOperations<?, ?> templateFromContext() {
        if (beanFactory == null) {
            return null;
        }
        for (String beanName : TEMPLATE_BEAN_NAMES) {
            KafkaOperations<?, ?> named = namedTemplate(beanName);
            if (named != null) {
                return named;
            }
        }
        return beanFactory.getBeanProvider(KafkaOperations.class).getIfUnique();
    }

    private KafkaOperations<?, ?> namedTemplate(String beanName) {
        try {
            return beanFactory.getBean(beanName, KafkaOperations.class);
        } catch (BeansException ex) {
            return null;
        }
    }

    private KafkaOperations<?, ?> ownDeadLetterTemplate() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put("value.serializer", StringSerializer.class.getName());
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
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
