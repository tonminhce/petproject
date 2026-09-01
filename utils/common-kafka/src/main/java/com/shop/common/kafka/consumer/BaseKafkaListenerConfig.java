package com.shop.common.kafka.consumer;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Base class for a typed Kafka listener container factory.
 *
 * <p>The <em>key</em> deserializer is fixed to {@link StringDeserializer}: the
 * fleet-wide producer convention is String keys, so records are always
 * deserialized with a String key regardless of the declared {@code K}. The
 * {@code keyType} constructor parameter is currently unused for
 * deserialization. The <em>value</em> path deserializes to {@code V} via
 * {@link JsonDeserializer}.</p>
 *
     * <p>Subclasses pin {@code K}/{@code V} and expose {@link #listenerContainerFactory()}
     * as a Spring bean so the container can be referenced by name from
     * {@code @KafkaListener}.</p>
     *
     * <p>D3 — container observation is enabled so spring-kafka's listener
     * instrumentation kicks in whenever the application context provides an
     * {@code ObservationRegistry}: the propagating tracing handler extracts the
     * W3C {@code traceparent} record header and the listener processing runs in
     * a child span of that remote parent. Without a registry on the classpath
     * (or without tracing handlers on it) the container degrades to a no-op —
     * a missing/unknown header still yields a new root trace.</p>
     *
 * <h3>Why wrap with {@link ErrorHandlingDeserializer}?</h3>
 * Poison records would otherwise tombstone the partition. Wrapping converts
 * deserialization failures into a {@code DeserializationException} that Spring
 * Kafka can handle without losing position. Note: routing those failures to a
 * dead-letter topic is <strong>not</strong> wired by this base class.
 */
public abstract class BaseKafkaListenerConfig<K, V> {

    private final Class<K> keyType;
    private final Class<V> valueType;
    private final KafkaProperties kafkaProperties;

    protected BaseKafkaListenerConfig(Class<K> keyType, Class<V> valueType, KafkaProperties kafkaProperties) {
        this.keyType = keyType;
        this.valueType = valueType;
        this.kafkaProperties = kafkaProperties;
    }

    public abstract ConcurrentKafkaListenerContainerFactory<K, V> listenerContainerFactory();

    protected ConcurrentKafkaListenerContainerFactory<K, V> kafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<K, V>();
        factory.setConsumerFactory(typedConsumerFactory());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    private ConsumerFactory<K, V> typedConsumerFactory() {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        return new DefaultKafkaConsumerFactory<>(
                props,
                BaseKafkaListenerConfig::keyDeserializer,
                () -> new ErrorHandlingDeserializer<>(jsonDeserializer(valueType))
        );
    }

    @SuppressWarnings("unchecked")
    private static <K> Deserializer<K> keyDeserializer() {
        return (Deserializer<K>) new StringDeserializer();
    }

    private static <T> JsonDeserializer<T> jsonDeserializer(Class<T> clazz) {
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(clazz);
        deserializer.addTrustedPackages("*");
        return deserializer;
    }
}
