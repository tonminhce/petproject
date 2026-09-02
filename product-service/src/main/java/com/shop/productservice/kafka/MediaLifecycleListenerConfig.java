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

/**
 * Listener container factory for the media lifecycle topic (media epic spec
 * D4). Standalone raw-string factory (predates the fleet-wide H-1 flip of
 * {@code BaseKafkaListenerConfig} to double-encode-tolerant string-based
 * consumers — same tolerance contract, self-unwrap shape). T4 GATE: the
 * fleet producer serializes the
 * outbox payload STRING via {@code JsonKafkaSerializer}, so records arrive
 * DOUBLE-ENCODED (a JSON string token wrapping the event JSON). The value
 * deserializes as RAW STRING ({@link StringDeserializer}, never failing on
 * poison bytes) and {@link MediaDeletedConsumer} performs the unwrap + typed
 * bind itself, which also stays tolerant of a future single-encoded relay.
 *
 * <p>Same fleet shape as the rating listener otherwise: {@code shop.kafka.*}
 * properties, container observation enabled (W3C traceparent extraction).</p>
 */
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
