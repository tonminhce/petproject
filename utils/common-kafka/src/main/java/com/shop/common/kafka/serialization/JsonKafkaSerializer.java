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
 * <p>Fleet wire contract (H-1): every producer publishes the outbox payload
 * STRING through this serializer, so a record value on the wire is the
 * JSON-string-encoded form of that payload — a JSON string token wrapping the
 * event JSON (DOUBLE-ENCODED). That shape is the ONLY sanctioned wire: every
 * consumer must tolerate it (see {@code BaseKafkaListenerConfig} /
 * {@code BaseKafkaConsumer}'s unwrap-once contract). Serializing a
 * non-String payload object yields single-encoded JSON — tolerated by
 * consumers but not produced by any fleet relay.</p>
 */
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
