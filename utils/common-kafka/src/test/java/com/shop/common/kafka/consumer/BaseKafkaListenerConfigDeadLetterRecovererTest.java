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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
class BaseKafkaListenerConfigDeadLetterRecovererTest {

    private static final String CONTEXT_TEMPLATE_TOPIC = "recoverer-context-template-topic";
    private static final String OWN_TEMPLATE_TOPIC = "recoverer-own-template-topic";
    private static final String POISON = "{ this is not json }";

    record OrderEvent(String eventId, String eventType, UUID orderId) {}

    private EmbeddedKafkaKraftBroker broker;
    private AnnotationConfigApplicationContext contextWithTemplate;
    private AnnotationConfigApplicationContext contextWithoutTemplate;

    @BeforeAll
    void startBrokerAndListeners() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, CONTEXT_TEMPLATE_TOPIC, OWN_TEMPLATE_TOPIC);
        broker.afterPropertiesSet();

        KafkaProperties contextTemplateProperties = new KafkaProperties();
        contextTemplateProperties.setBootstrapServers(broker.getBrokersAsString());
        contextTemplateProperties.getConsumer().setGroupId("recoverer-context-template-group");

        contextWithTemplate = new AnnotationConfigApplicationContext();
        contextWithTemplate.registerBean(KafkaProperties.class, () -> contextTemplateProperties);
        contextWithTemplate.getBeanFactory().registerSingleton("stringKafkaTemplate", kafkaTemplate());
        contextWithTemplate.register(ContextTemplateListenerConfig.class);
        contextWithTemplate.register(ContextTemplateConsumer.class);
        contextWithTemplate.refresh();

        KafkaProperties ownTemplateProperties = new KafkaProperties();
        ownTemplateProperties.setBootstrapServers(broker.getBrokersAsString());
        ownTemplateProperties.getConsumer().setGroupId("recoverer-own-template-group");

        contextWithoutTemplate = new AnnotationConfigApplicationContext();
        contextWithoutTemplate.registerBean(KafkaProperties.class, () -> ownTemplateProperties);
        contextWithoutTemplate.register(OwnTemplateListenerConfig.class);
        contextWithoutTemplate.register(OwnTemplateConsumer.class);
        contextWithoutTemplate.refresh();
    }

    @AfterAll
    void shutDown() {
        if (contextWithTemplate != null) {
            contextWithTemplate.close();
        }
        if (contextWithoutTemplate != null) {
            contextWithoutTemplate.close();
        }
        if (broker != null) {
            broker.destroy();
        }
    }

    @Test
    void aPoisonRecordIsDeadLetteredWhenTheConfigWasBuiltWithoutATemplate() throws Exception {
        publish(CONTEXT_TEMPLATE_TOPIC, POISON);

        String deadLettered = await(() -> consumeDeadLetterRecord(CONTEXT_TEMPLATE_TOPIC), Duration.ofSeconds(30));

        assertThat(deadLettered)
                .as("a record that exhausted its retries must be published to the dead-letter topic")
                .isNotNull();
        assertThat(ContextTemplateConsumer.RECEIVED)
                .as("the listener must never see the poison record")
                .isEmpty();
    }

    @Test
    void aPoisonRecordIsDeadLetteredWhenNoTemplateExistsAtAll() throws Exception {
        publish(OWN_TEMPLATE_TOPIC, POISON);

        String deadLettered = await(() -> consumeDeadLetterRecord(OWN_TEMPLATE_TOPIC), Duration.ofSeconds(30));

        assertThat(deadLettered)
                .as("a record that exhausted its retries must be published to the dead-letter topic")
                .isNotNull();
        assertThat(OwnTemplateConsumer.RECEIVED)
                .as("the listener must never see the poison record")
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

    private void publish(String topic, String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(topic, "key-1", value)).get();
        }
    }

    private String consumeDeadLetterRecord(String topic) {
        for (String deadLetterTopic : deadLetterTopics(topic)) {
            String record = consumeOne(deadLetterTopic);
            if (record != null) {
                return record;
            }
        }
        return null;
    }

    private List<String> deadLetterTopics(String topic) {
        try (var admin = org.apache.kafka.clients.admin.Admin.create(Map.of(
                "bootstrap.servers", broker.getBrokersAsString()))) {
            return admin.listTopics().names().get().stream()
                    .filter(t -> t.startsWith(topic) && !t.equals(topic))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Could not list the broker topics", e);
        }
    }

    private String consumeOne(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "recoverer-dlt-reader-" + UUID.randomUUID());
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
    static class ContextTemplateListenerConfig extends BaseKafkaListenerConfig<String> {

        ContextTemplateListenerConfig(KafkaProperties kafkaProperties) {
            super(String.class, kafkaProperties);
        }

        @Override
        @Bean(name = "contextTemplateFactory")
        public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
            return kafkaListenerContainerFactory();
        }
    }

    static class ContextTemplateConsumer extends BaseKafkaConsumer<String, OrderEvent> {

        static final BlockingQueue<OrderEvent> RECEIVED = new LinkedBlockingQueue<>();

        @KafkaListener(id = "recoverer-context-template",
                topics = CONTEXT_TEMPLATE_TOPIC,
                groupId = "recoverer-context-template-group",
                containerFactory = "contextTemplateFactory")
        void onRecord(String rawValue, MessageHeaders headers) {
            processMessage(rawValue, headers, OrderEvent.class, RECEIVED::add);
        }
    }

    @EnableKafka
    static class OwnTemplateListenerConfig extends BaseKafkaListenerConfig<String> {

        OwnTemplateListenerConfig(KafkaProperties kafkaProperties) {
            super(String.class, kafkaProperties);
        }

        @Override
        @Bean(name = "ownTemplateFactory")
        public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory() {
            return kafkaListenerContainerFactory();
        }
    }

    static class OwnTemplateConsumer extends BaseKafkaConsumer<String, OrderEvent> {

        static final BlockingQueue<OrderEvent> RECEIVED = new LinkedBlockingQueue<>();

        @KafkaListener(id = "recoverer-own-template",
                topics = OWN_TEMPLATE_TOPIC,
                groupId = "recoverer-own-template-group",
                containerFactory = "ownTemplateFactory")
        void onRecord(String rawValue, MessageHeaders headers) {
            processMessage(rawValue, headers, OrderEvent.class, RECEIVED::add);
        }
    }
}
