package com.shop.common.kafka.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Jackson-based JSON serializer used by the platform's Kafka producer.
 * Falls back to the string representation of the payload when Jackson cannot
 * serialise it so the producer never blocks the caller.
 *
 * <p>Wire context (R1 + H-1): serialising a payload STRING through this
 * serializer JSON-string-encodes it again — a JSON string token wrapping the
 * event JSON (DOUBLE-ENCODED). That was the pre-R1 fleet wire; consumers'
 * unwrap-once boundary ({@code BaseKafkaListenerConfig} /
 * {@code BaseKafkaConsumer}) still tolerates it as a legacy in-flight shape,
 * but the production producer path is single-encoded JSON-as-String via
 * {@code KafkaMessagePublisher} (R1). Serializing a non-String payload object
 * yields single-encoded JSON.</p>
 *
 * @deprecated since 2026-09-02 — use {@link com.shop.common.kafka.producer.KafkaMessagePublisher}
 *             (which uses {@code StringSerializer}) for outbox producers. The fleet
 *             contract is JSON-as-String and serialising through Jackson again
 *             causes the R1 double-encoding bug this class was the last to
 *             trigger. Retained only for non-outbox callers that genuinely want
 *             a typed JSON envelope; will be removed once all such callers have
 *             migrated.
 */
@Deprecated(since = "2026-09-02", forRemoval = true)
public class JsonKafkaSerializer<T> implements Serializer<T> {

    private final ObjectMapper objectMapper;

    public JsonKafkaSerializer() {
        this(new ObjectMapper());
    }

    public JsonKafkaSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No-op: configuration is provided via constructor.
    }

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception ex) {
            // last-resort fallback: keep the producer non-blocking
            return data.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte[] serialize(String topic, Headers headers, T data) {
        return serialize(topic, data);
    }

    @Override
    public void close() {
        // No resources to release.
    }
}
