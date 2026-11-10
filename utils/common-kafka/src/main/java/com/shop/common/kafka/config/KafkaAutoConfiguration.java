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

/**
 * Auto-wires the shared {@link KafkaTemplate} instances and {@link KafkaMessagePublisher}.
 * <p>Activated whenever {@code spring-kafka} is on the classpath and
 * {@code shop.kafka.enabled} is not explicitly set to {@code false}.</p>
 * <h3>String passthrough</h3>
 * The fleet's outbox producers all store {@code payload} as a JSON-as-String and pass
 * it to {@link KafkaMessagePublisher#publish}. Before this fix the shared
 * {@code kafkaTemplate} used {@link JsonKafkaSerializer}, which wrapped the String
 * into another JSON layer on the wire — consumers with {@code JsonDeserializer<V>}
 * then failed to bind (typed {@code ErrorHandlingDeserializer} wrapped the failure,
 * the default error handler retried 9 times, then silently dropped the record).
 * <p>The fix introduces a second template, {@code stringKafkaTemplate}, that uses
 * {@link StringSerializer} for both key and value. {@link KafkaMessagePublisher}
 * now uses this template and forwards values as raw UTF-8 bytes — exactly one
 * layer of JSON on the wire, which typed consumers can decode.</p>
 * <p>The original {@code kafkaTemplate} bean is kept (with its {@code JsonKafkaSerializer})
 * for any non-outbox caller that genuinely wants a typed JSON envelope — callers
 * outside the outbox pattern. New outbox producers should NOT use it; use
 * {@link KafkaMessagePublisher} instead.</p>
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
        var props = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        props.put("value.serializer", serializer.getClass().getName());
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Outbox-friendly producer that ships the payload as raw UTF-8 bytes
     * (single-encoded JSON, not double-encoded). Used by {@link KafkaMessagePublisher}
     * and indirectly by every outbox relay across the fleet.
     */
    @Bean(name = "stringKafkaTemplate")
    public KafkaTemplate<String, String> stringKafkaTemplate(KafkaProperties kafkaProperties) {
        var props = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    /**
     * F-017 - the replay path for records that were dead-lettered. Without it
     * the dead-letter topic only makes the loss visible; the operator has
     * nothing to send the record back with.
     */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterReplayService deadLetterReplayService(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> stringKafkaTemplate,
            KafkaProperties kafkaProperties) {
        return new DeadLetterReplayService(stringKafkaTemplate,
                kafkaProperties.getBootstrapServers(), "shop");
    }

    /**
     * F-017 — the operational replay entry point. OFF by default: the bean is
     * only registered when the operator explicitly enables it, and it is a
     * WRITE operation, so it is not reachable over the web unless it is also
     * added to management.endpoints.web.exposure.include.
     */
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
