package com.shop.common.kafka.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
    }

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception ex) {
            return data.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte[] serialize(String topic, Headers headers, T data) {
        return serialize(topic, data);
    }

    @Override
    public void close() {
    }
}
