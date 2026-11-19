package com.shop.common.kafka.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class JsonKafkaDeserializer<T> implements Deserializer<T> {

    private final Class<T> targetType;
    private final ObjectMapper objectMapper;

    public JsonKafkaDeserializer(Class<T> targetType) {
        this(targetType, new ObjectMapper());
    }

    public JsonKafkaDeserializer(Class<T> targetType, ObjectMapper objectMapper) {
        this.targetType = targetType;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(data, targetType);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialize Kafka payload for topic " + topic, ex);
        }
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        return deserialize(topic, data);
    }

    @Override
    public void close() {
    }

    public static byte[] toBytes(ObjectMapper mapper, Object payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialise test payload", ex);
        }
    }

    public static String toString(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
