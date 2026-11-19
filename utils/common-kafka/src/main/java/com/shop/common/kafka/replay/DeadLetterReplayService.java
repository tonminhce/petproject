package com.shop.common.kafka.replay;

import com.shop.common.kafka.contract.KafkaTopicContract;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

    public static final String ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";
    public static final String ORIGINAL_PARTITION_HEADER = "kafka_dlt-original-partition";
    public static final String ORIGINAL_OFFSET_HEADER = "kafka_dlt-original-offset";

    private final KafkaTemplate<String, String> template;
    private final String bootstrapServers;
    private final String groupIdPrefix;

    public DeadLetterReplayService(KafkaTemplate<String, String> template,
                                  String bootstrapServers,
                                  String groupIdPrefix) {
        this.template = template;
        this.bootstrapServers = bootstrapServers;
        this.groupIdPrefix = groupIdPrefix;
    }

    public record ReplayResult(int replayed, int skipped, int failed, List<String> replayedTopics) {
        public static ReplayResult empty() {
            return new ReplayResult(0, 0, 0, List.of());
        }
    }

    public ReplayResult replay(String deadLetterTopic, int maxRecords, int maxConsecutiveFailures) {
        if (deadLetterTopic == null || deadLetterTopic.isBlank()) {
            throw new IllegalArgumentException("deadLetterTopic must be set");
        }
        if (maxRecords <= 0) {
            return ReplayResult.empty();
        }
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, replayGroupId(deadLetterTopic));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        int replayed = 0;
        int skipped = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        List<String> replayedTopics = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(deadLetterTopic));
            while (replayed + skipped + failed < maxRecords) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
                    if (consumer.paused().contains(topicPartition)) {
                        continue;
                    }
                    String originalTopic = headerValue(record, ORIGINAL_TOPIC_HEADER);
                    if (originalTopic == null || originalTopic.isBlank()) {
                        skipped++;
                        log.warn("Dead-letter record {}-{}@{} carries no original-topic header - not replayable",
                                record.topic(), record.partition(), record.offset());
                        commit(consumer, topicPartition, record.offset() + 1);
                        continue;
                    }
                    if (KafkaTopicContract.find(originalTopic) == null) {
                        skipped++;
                        log.warn("Dead-letter record {}-{}@{} names the target topic {} which is not declared "
                                        + "in the Kafka topic contract - not replayed",
                                record.topic(), record.partition(), record.offset(), originalTopic);
                        commit(consumer, topicPartition, record.offset() + 1);
                        continue;
                    }
                    try {
                        template.send(new ProducerRecord<>(originalTopic, record.key(), record.value())).get();
                        replayed++;
                        replayedTopics.add(originalTopic);
                        consecutiveFailures = 0;
                        commit(consumer, topicPartition, record.offset() + 1);
                        log.info("Replayed dead-letter record {}-{}@{} back to {}",
                                record.topic(), record.partition(), record.offset(), originalTopic);
                    } catch (Exception ex) {
                        failed++;
                        consecutiveFailures++;
                        log.error("Could not replay dead-letter record {}-{}@{} to {}",
                                record.topic(), record.partition(), record.offset(), originalTopic, ex);
                        commit(consumer, topicPartition, record.offset());
                        consumer.seek(topicPartition, record.offset());
                        consumer.pause(Collections.singleton(topicPartition));
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            log.warn("Stopping the replay after {} consecutive failures", consecutiveFailures);
                            return new ReplayResult(replayed, skipped, failed, replayedTopics);
                        }
                    }
                }
            }
        }
        return new ReplayResult(replayed, skipped, failed, replayedTopics);
    }

    private String replayGroupId(String deadLetterTopic) {
        return groupIdPrefix + "-dlq-replay-" + deadLetterTopic;
    }

    private void commit(KafkaConsumer<String, String> consumer, TopicPartition topicPartition, long nextOffset) {
        consumer.commitSync(Map.of(topicPartition, new OffsetAndMetadata(nextOffset)));
    }

    private String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Map<String, Object> producerProperties(String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        return props;
    }
}
