package com.shop.common.kafka.consumer;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.messaging.MessageHeaders;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaseKafkaConsumerSchemaEvolutionTest {

    private static final String TOPIC = "schema-evolution-topic";
    private static final String ORDER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String EXTRA_FIELD_PAYLOAD = "{\"eventId\":\"e-1\",\"eventType\":\"test.event.v1\","
            + "\"orderId\":\"" + ORDER_ID + "\",\"addedByTheNextProducerVersion\":42}";
    private static final String MALFORMED_PAYLOAD = "{ this is not json }";

    record OrderEvent(String eventId, String eventType, UUID orderId) {}

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
        context.getBeanFactory().registerSingleton("stringKafkaTemplate", kafkaTemplate());
        context.register(SchemaEvolutionListenerConfig.class);
        context.register(SchemaEvolutionConsumer.class);
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
        SchemaEvolutionConsumer.RECEIVED.clear();
    }

    @Test
    void aRecordWithAnExtraFieldIsHandledAndNeverDeadLettered() throws Exception {
        publish(EXTRA_FIELD_PAYLOAD);

        OrderEvent handled = await(() -> SchemaEvolutionConsumer.RECEIVED.poll(), Duration.ofSeconds(30));

        assertThat(handled)
                .as("an additive change to the event contract must not break the consumer")
                .isNotNull();
        assertThat(handled.eventId()).isEqualTo("e-1");
        assertThat(handled.orderId()).isEqualTo(UUID.fromString(ORDER_ID));
    }

    @Test
    void aMalformedRecordIsDeadLetteredAndNeverHandled() throws Exception {
        publish(MALFORMED_PAYLOAD);

        String deadLettered = await(this::consumeDeadLetterRecord, Duration.ofSeconds(30));

        assertThat(deadLettered)
                .as("a genuinely malformed record must reach the dead-letter topic")
                .isNotNull();
        assertThat(SchemaEvolutionConsumer.RECEIVED)
                .as("the listener must never see an unbindable payload")
                .isEmpty();
    }

    private KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    private void publish(String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(TOPIC, "key-1", value)).get();
        }
    }

    private String consumeDeadLetterRecord() {
        for (String topic : deadLetterTopics()) {
            String record = consumeOne(topic);
            if (record != null) {
                return record;
            }
        }
        return null;
    }

    private List<String> deadLetterTopics() {
        try (var admin = org.apache.kafka.clients.admin.Admin.create(Map.of(
                "bootstrap.servers", broker.getBrokersAsString()))) {
            return admin.listTopics().names().get().stream()
                    .filter(t -> t.startsWith(TOPIC) && !t.equals(TOPIC))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Could not list the broker topics", e);
        }
    }

    private String consumeOne(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "schema-evolution-dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    return record.value();
                }
            }
        }
        return null;
    }

    private static <T> T await(Supplier<T> probe, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T result;
        do {
            result = probe.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(200L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    @EnableKafka
    static class SchemaEvolutionListenerConfig extends BaseKafkaListenerConfig<String> {

        SchemaEvolutionListenerConfig(KafkaProperties kafkaProperties,
                                       @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
            super(String.class, kafkaProperties, kafkaTemplate);
        }

        @Override
        @Bean(name = "schemaEvolutionFactory")
        public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
            return kafkaListenerContainerFactory();
        }
    }

    static class SchemaEvolutionConsumer extends BaseKafkaConsumer<String, OrderEvent> {

        static final BlockingQueue<OrderEvent> RECEIVED = new LinkedBlockingQueue<>();

        @KafkaListener(id = "schema-evolution-test", topics = TOPIC, containerFactory = "schemaEvolutionFactory")
        void onRecord(String rawValue, MessageHeaders headers) {
            processMessage(rawValue, headers, OrderEvent.class, RECEIVED::add);
        }
    }
}
