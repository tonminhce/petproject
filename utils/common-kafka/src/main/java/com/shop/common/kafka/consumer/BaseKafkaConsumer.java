package com.shop.common.kafka.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Template-method base class for Kafka consumers. Two flavors of
 * {@code processMessage}:
 *
 * <ul>
 *   <li>Value-only: when the key is not relevant to the handler logic.</li>
 *   <li>Key + value: when the consumer needs to dedupe or partition by key.</li>
 * </ul>
 *
 * @param <K> key type
 * @param <V> value type
 */
public abstract class BaseKafkaConsumer<K, V> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

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
}
