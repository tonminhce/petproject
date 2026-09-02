package com.shop.common.kafka.consumer;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-1 wire pin: the {@link BaseKafkaListenerConfig} factory delivers the RAW
 * STRING record value to the listener — for both the production
 * single-encoded wire (R1: KafkaMessagePublisher over StringSerializer ×2)
 * and the legacy double-encoded shape (a JSON string token wrapping the event
 * JSON; H-1 defense-in-depth for pre-R1 in-flight records). Under the
 * previous {@code JsonDeserializer} wiring the double-encoded publish failed
 * value deserialization before the listener ever ran (the silent-drop footgun
 * this base flip removes), so these asserts double as the old-vs-new pin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaseKafkaListenerConfigWireTest {

    private static final String TOPIC = "wire-shape-topic";
    private static final String TOKEN =
            "\"{\\\"eventId\\\":\\\"e-1\\\",\\\"eventType\\\":\\\"test.event.v1\\\"}\"";
    private static final String SINGLE_ENCODED = "{\"eventId\":\"e-2\",\"eventType\":\"test.event.v1\"}";

    private EmbeddedKafkaKraftBroker broker;
    private AnnotationConfigApplicationContext context;

    @BeforeAll
    void startBrokerAndListener() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(broker.getBrokersAsString());

        context = new AnnotationConfigApplicationContext();
        context.registerBean(KafkaProperties.class, () -> kafkaProperties);
        context.register(WireTestListenerConfig.class);
        context.refresh();
    }

    @AfterAll
    void shutDown() {
        if (context != null) {
            context.close();
        }
        if (broker != null) {
            broker.destroy();
        }
    }

    @BeforeEach
    void resetQueue() {
        WireTestListenerConfig.RECEIVED.clear();
    }

    @Test
    void doubleEncodedTokenReachesListenerAsRawString() throws Exception {
        publish(TOKEN, "token-key");

        String raw = await(() -> WireTestListenerConfig.RECEIVED.poll(), Duration.ofSeconds(15));
        assertThat(raw).as("raw string token on the wire, not a bound DTO").isEqualTo(TOKEN);
    }

    @Test
    void singleEncodedJsonAlsoReachesListenerAsRawString() throws Exception {
        publish(SINGLE_ENCODED, "single-key");

        String raw = await(() -> WireTestListenerConfig.RECEIVED.poll(), Duration.ofSeconds(15));
        assertThat(raw).isEqualTo(SINGLE_ENCODED);
    }

    private void publish(String value, String key) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(TOPIC, key, value)).get();
        }
    }

    private static <T> T await(Supplier<T> probe, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T result;
        do {
            result = probe.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    @EnableKafka
    static class WireTestListenerConfig extends BaseKafkaListenerConfig<String> {

        static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

        WireTestListenerConfig(KafkaProperties kafkaProperties) {
            super(String.class, kafkaProperties);
        }

        @Override
        @Bean(name = "wireTestFactory")
        public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
            return kafkaListenerContainerFactory();
        }

        @KafkaListener(id = "wire-test", topics = TOPIC, containerFactory = "wireTestFactory")
        void onRecord(String rawValue) {
            RECEIVED.add(rawValue);
        }
    }
}
