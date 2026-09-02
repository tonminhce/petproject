package com.shop.searchservice.kafka;

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
 * Listener container factory for the product lifecycle topic (search spec D1
 * — last hop of the product→search refresh chain, F-5). Standalone raw-string
 * factory (predates the fleet-wide H-1 flip of {@code BaseKafkaListenerConfig}
 * to double-encode-tolerant string-based consumers — same tolerance contract,
 * self-unwrap shape). The fleet producer serializes
 * the outbox payload STRING via {@code JsonKafkaSerializer} (the product
 * OutboxRelay path), so records arrive DOUBLE-ENCODED — a JSON string token
 * wrapping the event JSON — and binding that token directly to the DTO throws
 * before the listener ever runs. The value therefore deserializes as RAW
 * STRING ({@link StringDeserializer}, never failing on poison bytes) and
 * {@link ProductSearchConsumer} performs the unwrap + typed bind itself
 * (search spec §4.2 unwrap-once contract), which also stays tolerant of a
 * future single-encoded relay.
 *
 * <p>Same fleet shape as product's media listener otherwise:
 * {@code shop.kafka.*} properties, container observation enabled (W3C
 * traceparent extraction).</p>
 */
@EnableKafka
@Configuration
public class SearchListenerConfig {

    @Bean(name = "searchListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> searchListenerFactory(
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
