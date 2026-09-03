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

/**
 * Base class for a typed Kafka listener container factory with Dead Letter Topic (DLT) recovery support.
 *
 * <h3>Fleet wire contract (R1 + H-1)</h3>
 *
 * <p>The <em>value</em> deserializes as RAW STRING ({@link StringDeserializer}).
 * Producers: JSON-as-String via {@code KafkaMessagePublisher} →
 * {@code stringKafkaTemplate} (StringSerializer ×2, R1) — records arrive
 * SINGLE-ENCODED UTF-8 JSON on the wire. Consumers: unwrap-once at the
 * {@link BaseKafkaConsumer} boundary, tolerant of BOTH the production
 * single-encoded wire AND the legacy double-encoded shape (a JSON string
 * token wrapping the event JSON) that in-flight/retained records may still
 * carry (H-1 defense-in-depth). Binding a record straight to a DTO (the
 * previous {@code JsonDeserializer} wiring) throws before the listener ever
 * runs and silently drops real records — which is why the base no longer
 * offers a typed value path; a decode failure is a contained ack-skip there,
 * never a listener-container crash.</p>
 *
 * <p>The <em>key</em> deserializer is likewise fixed to
 * {@link StringDeserializer}: the fleet-wide producer convention is String
 * keys, so records are always deserialized with a String key regardless of the
 * declared {@code K}. The {@code keyType} constructor parameter is currently
 * unused for deserialization.</p>
 *
 * <p>Subclasses pin {@code K} and expose {@link #listenerContainerFactory()}
 * as a Spring bean so the container can be referenced by name from
 * {@code @KafkaListener}. {@code StringDeserializer} never throws — poison
 * bytes cannot tombstone a partition at deserialize time, so the previous
 * {@code ErrorHandlingDeserializer} wrapper is unnecessary.</p>
 *
 * <p>D3 — container observation is enabled so spring-kafka's listener
 * instrumentation kicks in whenever the application context provides an
 * {@code ObservationRegistry}: the propagating tracing handler extracts the
 * W3C {@code traceparent} record header and the listener processing runs in
 * a child span of that remote parent. Without a registry on the classpath
 * (or without tracing handlers on it) the container degrades to a no-op —
 * a missing/unknown header still yields a new root trace.</p>
 */
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
