package com.shop.common.kafka.replay;

import com.shop.common.kafka.contract.KafkaTopicContract;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeadLetterReplayOffsetTest {

    private static final String FIRST_TOPIC = KafkaTopicContract.SHOP_ORDER_LIFECYCLE;
    private static final String SECOND_TOPIC = KafkaTopicContract.SHOP_PAYMENT_LIFECYCLE;
    private static final String FIRST_DEAD_LETTER_TOPIC = FIRST_TOPIC + "-dlt";
    private static final String SECOND_DEAD_LETTER_TOPIC = SECOND_TOPIC + "-dlt";
    private static final String FIRST_PAYLOAD = "{\"eventId\":\"e-1\",\"eventType\":\"test.event.v1\"}";
    private static final String SECOND_PAYLOAD = "{\"eventId\":\"e-2\",\"eventType\":\"test.event.v1\"}";
    private static final String THIRD_PAYLOAD = "{\"eventId\":\"e-3\",\"eventType\":\"test.event.v1\"}";

    private EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, FIRST_TOPIC, SECOND_TOPIC, FIRST_DEAD_LETTER_TOPIC,
                SECOND_DEAD_LETTER_TOPIC);
        broker.afterPropertiesSet();
    }

    @AfterAll
    void shutDown() {
        if (broker != null) {
            broker.destroy();
        }
    }

    @Test
    void twoConsecutiveReplayPassesPublishEachRecordOnceInTotal() throws Exception {
        publish(FIRST_DEAD_LETTER_TOPIC, FIRST_PAYLOAD);
        publish(FIRST_DEAD_LETTER_TOPIC, SECOND_PAYLOAD);

        DeadLetterReplayService replay = new DeadLetterReplayService(
                kafkaTemplate(), broker.getBrokersAsString(), "replay-once");

        DeadLetterReplayService.ReplayResult firstPass = replay.replay(FIRST_DEAD_LETTER_TOPIC, 10, 3);
        DeadLetterReplayService.ReplayResult secondPass = replay.replay(FIRST_DEAD_LETTER_TOPIC, 10, 3);

        assertThat(firstPass.replayed()).isEqualTo(2);
        assertThat(firstPass.failed()).isZero();
        assertThat(secondPass.replayed())
                .as("a second pass must not republish what the first pass already replayed")
                .isZero();

        assertThat(countOnTopic(FIRST_TOPIC, FIRST_PAYLOAD)).isEqualTo(1);
        assertThat(countOnTopic(FIRST_TOPIC, SECOND_PAYLOAD)).isEqualTo(1);
        assertThat(committedOffset("replay-once-dlq-replay-" + FIRST_DEAD_LETTER_TOPIC, FIRST_DEAD_LETTER_TOPIC))
                .as("the replay must keep its offsets across runs")
                .isEqualTo(2L);
    }

    @Test
    void aRecordThatFailedToRepublishIsRetriedOnTheNextPass() throws Exception {
        publish(SECOND_DEAD_LETTER_TOPIC, FIRST_PAYLOAD);
        publish(SECOND_DEAD_LETTER_TOPIC, SECOND_PAYLOAD);
        publish(SECOND_DEAD_LETTER_TOPIC, THIRD_PAYLOAD);

        DeadLetterReplayService failingReplay = new DeadLetterReplayService(
                rejectingTemplate(SECOND_PAYLOAD), broker.getBrokersAsString(), "replay-retry");

        DeadLetterReplayService.ReplayResult firstPass =
                failingReplay.replay(SECOND_DEAD_LETTER_TOPIC, 10, 3);

        assertThat(firstPass.replayed()).isEqualTo(1);
        assertThat(firstPass.failed()).isEqualTo(1);
        assertThat(committedOffset("replay-retry-dlq-replay-" + SECOND_DEAD_LETTER_TOPIC, SECOND_DEAD_LETTER_TOPIC))
                .as("the offset of a record that failed to republish must not advance")
                .isEqualTo(1L);

        DeadLetterReplayService replay = new DeadLetterReplayService(
                kafkaTemplate(), broker.getBrokersAsString(), "replay-retry");

        DeadLetterReplayService.ReplayResult secondPass = replay.replay(SECOND_DEAD_LETTER_TOPIC, 10, 3);

        assertThat(secondPass.replayed())
                .as("the record that failed to republish must be retried on the next pass")
                .isEqualTo(2);
        assertThat(secondPass.failed()).isZero();

        assertThat(countOnTopic(SECOND_TOPIC, FIRST_PAYLOAD)).isEqualTo(1);
        assertThat(countOnTopic(SECOND_TOPIC, SECOND_PAYLOAD)).isEqualTo(1);
        assertThat(countOnTopic(SECOND_TOPIC, THIRD_PAYLOAD)).isEqualTo(1);
    }

    private KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    private KafkaTemplate<String, String> rejectingTemplate(String rejectedValue) {
        return new RejectingTemplate(kafkaTemplate().getProducerFactory(), rejectedValue);
    }

    private void publish(String topic, String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, "key-1", value);
            record.headers().add(DeadLetterReplayService.ORIGINAL_TOPIC_HEADER,
                    originalTopicOf(topic).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
    }

    private String originalTopicOf(String deadLetterTopic) {
        return KafkaTopicContract.originalTopicOf(deadLetterTopic);
    }

    private long committedOffset(String groupId, String topic) throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata offset = offsets.get(new TopicPartition(topic, 0));
            return offset == null ? -1L : offset.offset();
        }
    }

    private int countOnTopic(String topic, String value) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-offset-counter-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        int seen = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
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

    static class RejectingTemplate extends KafkaTemplate<String, String> {

        private final String rejectedValue;

        RejectingTemplate(ProducerFactory<String, String> producerFactory, String rejectedValue) {
            super(producerFactory);
            this.rejectedValue = rejectedValue;
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            if (rejectedValue.equals(record.value())) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("the broker refused the publish"));
            }
            return super.send(record);
        }
    }
}
