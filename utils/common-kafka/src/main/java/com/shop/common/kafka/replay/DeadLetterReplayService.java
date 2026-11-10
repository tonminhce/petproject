package com.shop.common.kafka.replay;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F-017 — replays records that were dead-lettered.
 *
 * <p>Publishing a poison record to a dead-letter topic only makes the loss
 * VISIBLE. Without a replay path the event is still lost: the operator sees a
 * record on the DLQ but there is nothing to send it back with. This service
 * closes that half — it reads a dead-letter topic and republishes each record
 * to the topic it originally came from.</p>
 *
 * <p>The original topic, partition and offset are taken from the headers that
 * Spring Kafka's {@code DeadLetterPublishingRecoverer} adds
 * ({@code kafka_dlt-original-topic} and friends), so the replay does not have
 * to guess the dead-letter naming scheme.</p>
 *
 * <p>The replay is deliberately BOUNDED and never automatic:</p>
 * <ul>
 *   <li>it replays at most {@code maxRecords} records per call, so an operator
 *       stays in control;</li>
 *   <li>it stops after {@code maxConsecutiveFailures} consecutive publish
 *       failures, so a broker outage does not spin;</li>
 *   <li>a record that is still poisonous is simply dead-lettered again by the
 *       consumer — the loop is bounded by the operator, not by the system.</li>
 * </ul>
 */
public class DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

    /** Header Spring Kafka adds with the topic the record was consumed from. */
    public static final String ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";
    /** Header Spring Kafka adds with the partition the record was consumed from. */
    public static final String ORIGINAL_PARTITION_HEADER = "kafka_dlt-original-partition";
    /** Header Spring Kafka adds with the offset the record was consumed from. */
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

    /**
     * The outcome of one replay pass.
     *
     * @param replayed  records republished to their original topic
     * @param skipped   records without an original-topic header (not replayable)
     * @param failed    records that could not be republished
     */
    public record ReplayResult(int replayed, int skipped, int failed, List<String> replayedTopics) {
        public static ReplayResult empty() {
            return new ReplayResult(0, 0, 0, List.of());
        }
    }

    /**
     * Replay up to {@code maxRecords} records from a dead-letter topic.
     *
     * @param deadLetterTopic the topic the records were dead-lettered onto
     * @param maxRecords       the hard cap for this pass
     * @param maxConsecutiveFailures stop after this many consecutive publish failures
     */
    public ReplayResult replay(String deadLetterTopic, int maxRecords, int maxConsecutiveFailures) {
        if (deadLetterTopic == null || deadLetterTopic.isBlank()) {
            throw new IllegalArgumentException("deadLetterTopic must be set");
        }
        if (maxRecords <= 0) {
            return ReplayResult.empty();
        }
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupIdPrefix + "-dlq-replay-" + System.nanoTime());
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
                    if (consecutiveFailures == 0) {
                        break; // drained
                    }
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    String originalTopic = headerValue(record, ORIGINAL_TOPIC_HEADER);
                    if (originalTopic == null || originalTopic.isBlank()) {
                        skipped++;
                        log.warn("Dead-letter record {}-{}@{} carries no original-topic header - not replayable",
                                record.topic(), record.partition(), record.offset());
                        consumer.commitSync();
                        continue;
                    }
                    try {
                        template.send(new ProducerRecord<>(originalTopic, record.key(), record.value())).get();
                        replayed++;
                        replayedTopics.add(originalTopic);
                        consecutiveFailures = 0;
                        log.info("Replayed dead-letter record {}-{}@{} back to {}",
                                record.topic(), record.partition(), record.offset(), originalTopic);
                    } catch (Exception ex) {
                        failed++;
                        consecutiveFailures++;
                        log.error("Could not replay dead-letter record {}-{}@{} to {}",
                                record.topic(), record.partition(), record.offset(), originalTopic, ex);
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            log.warn("Stopping the replay after {} consecutive failures", consecutiveFailures);
                            consumer.commitSync();
                            return new ReplayResult(replayed, skipped, failed, replayedTopics);
                        }
                    }
                    consumer.commitSync();
                }
            }
        }
        return new ReplayResult(replayed, skipped, failed, replayedTopics);
    }

    private String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The producer configuration a caller needs to publish into the DLQ by hand. */
    public static Map<String, Object> producerProperties(String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        return props;
    }
}
