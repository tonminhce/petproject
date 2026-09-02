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
 * {@code BaseKafkaListenerConfig} to string-based consumers — same tolerance
 * contract, self-unwrap shape). Wire contract (R1 + H-1): producers publish
 * JSON-as-String via {@code KafkaMessagePublisher}, so records arrive
 * SINGLE-ENCODED UTF-8 JSON; the value deserializes as RAW STRING
 * ({@link StringDeserializer}, never failing on poison bytes) and
 * {@link MediaDeletedConsumer} performs the unwrap + typed bind itself,
 * which also stays tolerant of the legacy double-encoded shape (a JSON
 * string token wrapping the event JSON) that in-flight/retained records may
 * still carry (H-1 defense-in-depth).
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
