package com.shop.common.kafka.replay;

import com.shop.common.kafka.config.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
class DeadLetterReplayServiceTest {

    private static final String TOPIC = "replay-round-trip-topic";
    private static final String POISON = "{ this is not json }";
    private static final String GOOD = "{\"eventId\":\"e-1\",\"eventType\":\"test.event.v1\"}";

    record OrderEvent(String eventId, String eventType) {}

    private EmbeddedKafkaKraftBroker broker;
    private AnnotationConfigApplicationContext context;
    private KafkaTemplate<String, String> template;

    @BeforeAll
    void startBrokerAndListener() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();

        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(producerProps);
        template = new KafkaTemplate<>(factory);

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(broker.getBrokersAsString());

        context = new AnnotationConfigApplicationContext();
        context.registerBean(KafkaProperties.class, () -> kafkaProperties);
        context.getBeanFactory().registerSingleton("stringKafkaTemplate", template);
        context.register(ReplayListenerConfig.class);
        context.register(ReplayConsumer.class);
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

    @Test
    void deadLetteredRecord_isReplayedBackToItsOriginalTopic() throws Exception {
        publish(POISON);

        DeadLetterReplayService replay = new DeadLetterReplayService(template,
                broker.getBrokersAsString(), "shop");
        String deadLetterTopic = awaitDeadLetterTopic();

        // The record is dead-lettered first.
        assertThat(await(() -> consumeOne(deadLetterTopic), Duration.ofSeconds(30)))
                .as("the poison record must reach the dead-letter topic")
                .isNotNull();

        DeadLetterReplayService.ReplayResult result = replay.replay(deadLetterTopic, 10, 3);

        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.replayedTopics()).contains(TOPIC);

        // The replay put the record back on the topic it came from, so the
        // original topic now carries the payload twice. The replayed copy is
        // still poisonous - it is dead-lettered again - which is exactly why
        // the replay is a bounded, operator-driven action and never automatic.
        assertThat(countOnTopic(TOPIC, POISON, Duration.ofSeconds(30)))
                .as("the replayed record must be republished on its original topic")
                .isGreaterThanOrEqualTo(2);
    }

    /** The dead-letter topic is created by the recoverer, so it appears a moment later. */
    private String awaitDeadLetterTopic() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                String topic = firstDeadLetterTopic();
                if (topic != null) {
                    return topic;
                }
            } catch (Exception ignored) {
                // the broker may not list the topic yet
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("no dead-letter topic was created");
    }

    private int countOnTopic(String topic, String value, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-counter-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        int seen = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                if (records.isEmpty() && seen > 0) {
                    break;
                }
                for (var record : records) {
                    if (value.equals(record.value())) {
                        seen++;
                    }
                }
            }
        }
        return seen;
    }

    private void publish(String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(TOPIC, "key-1", value)).get();
        }
    }

    private String firstDeadLetterTopic() {
        try (var admin = org.apache.kafka.clients.admin.Admin.create(Map.of(
                "bootstrap.servers", broker.getBrokersAsString()))) {
            return admin.listTopics().names().get().stream()
                    .filter(t -> t.startsWith(TOPIC) && !t.equals(TOPIC))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            throw new IllegalStateException("Could not list the broker topics", e);
        }
    }

    private String consumeOne(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
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
    static class ReplayListenerConfig extends com.shop.common.kafka.consumer.BaseKafkaListenerConfig<String> {

        ReplayListenerConfig(KafkaProperties kafkaProperties,
                             @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
            super(String.class, kafkaProperties, kafkaTemplate);
        }

        @Override
        @Bean(name = "replayTestFactory")
        public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
            return kafkaListenerContainerFactory();
        }
    }

    static class ReplayConsumer extends com.shop.common.kafka.consumer.BaseKafkaConsumer<String, OrderEvent> {

        static final BlockingQueue<OrderEvent> RECEIVED = new LinkedBlockingQueue<>();

        @KafkaListener(id = "replay-test", topics = TOPIC, containerFactory = "replayTestFactory")
        void onRecord(String rawValue, MessageHeaders headers) {
            processMessage(rawValue, headers, OrderEvent.class, RECEIVED::add);
        }
    }
}
