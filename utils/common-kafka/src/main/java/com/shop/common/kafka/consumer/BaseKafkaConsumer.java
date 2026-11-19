package com.shop.common.kafka.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class BaseKafkaConsumer<K, V> {

    private static final ObjectMapper WIRE_MAPPER = KafkaWireMapper.mapper();

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected void processMessage(String rawValue, MessageHeaders headers, Class<V> type, Consumer<V> handler) {
        V event = decodeContained(rawValue, type);
        if (event == null) {
            return;
        }
        processMessage(event, headers, handler);
    }

    protected void processMessage(V record, MessageHeaders headers, Consumer<V> handler) {
        Object key = headers.get(KafkaHeaders.RECEIVED_KEY);
        if (log.isDebugEnabled()) {
            log.debug("Received headers={}", headers);
            log.debug("Processing key={} value={}", key, record);
        }
        handler.accept(record);
        if (log.isDebugEnabled()) {
            log.debug("Processed key={}", key);
        }
    }

    protected void processMessage(K key, V value, MessageHeaders headers, BiConsumer<K, V> handler) {
        if (log.isDebugEnabled()) {
            log.debug("Received headers={}", headers);
            log.debug("Processing key={} value={}", key, value);
        }
        handler.accept(key, value);
        if (log.isDebugEnabled()) {
            log.debug("Processed key={}", key);
        }
    }

    private V decodeContained(String rawValue, Class<V> type) {
        if (rawValue == null) {
            log.info("Tombstone record (null value) for {} — ack-skipping", type.getSimpleName());
            return null;
        }
        try {
            JsonNode node = WIRE_MAPPER.readTree(rawValue);
            if (node.isString()) {
                node = WIRE_MAPPER.readTree(node.stringValue());
            }
            V bound = WIRE_MAPPER.treeToValue(node, type);
            if (bound == null) {
                throw new IllegalStateException(
                        "Payload did not bind to " + type.getSimpleName());
            }
            return bound;
        } catch (Exception ex) {
            log.error("Failed to decode Kafka payload for {} — routing to the dead-letter topic: {}",
                    type.getSimpleName(), rawValue, ex);
            throw new PoisonRecordException(
                    "Undecodable Kafka payload for " + type.getSimpleName(), ex);
        }
    }
}
