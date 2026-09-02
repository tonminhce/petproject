package com.shop.common.kafka.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Template-method base class for Kafka consumers. Three flavors of
 * {@code processMessage}:
 *
 * <ul>
 *   <li>Raw-wire + typed bind (H-1): the listener hands over the RAW STRING
 *       value (see {@link BaseKafkaListenerConfig}'s wire contract) plus the
 *       DTO class; the base unwraps-once and binds the typed event before the
 *       handler sees it. Decode failures are contained ack-skips — the
 *       listener method never throws and the offset always advances.</li>
 *   <li>Value-only (typed event already bound): when the key is not relevant
 *       to the handler logic.</li>
 *   <li>Key + value: when the consumer needs to dedupe or partition by
 *       key.</li>
 * </ul>
 *
 * <p>The wire mapper is a shared {@link ObjectMapper} with
 * {@link JavaTimeModule} so ISO-8601 instants on the fleet wire (e.g.
 * {@code transitionedAt}) bind like they do on the producer side (Boot's
 * mapper also registers jsr310). Trusted-package configuration is not needed:
 * binding targets a concrete DTO class, never polymorphic types.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
public abstract class BaseKafkaConsumer<K, V> {

    private static final ObjectMapper WIRE_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Raw-wire entry: decode the record value — the production wire is
     * single-encoded JSON (R1); a legacy double-encoded token (pre-R1
     * in-flight shape, H-1 tolerance) is unwrapped first — and hand the
     * bound event to the typed handler. A decode failure logs at ERROR and
     * ack-skips without invoking the handler (fleet containment: no DLT, no
     * container crash).
     */
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

    /**
     * Unwrap-once + typed bind: a textual top-level node (the LEGACY
     * double-encoded wire — a JSON string token wrapping the event JSON;
     * H-1 defense-in-depth for in-flight records) is unwrapped and parsed
     * again; a non-textual node binds directly (the production single-encoded
     * wire since R1). Any decode failure returns {@code null} so the caller
     * ack-skips.
     */
    private V decodeContained(String rawValue, Class<V> type) {
        if (rawValue == null) {
            log.info("Tombstone record (null value) for {} — ack-skipping", type.getSimpleName());
            return null;
        }
        try {
            JsonNode node = WIRE_MAPPER.readTree(rawValue);
            if (node.isTextual()) {
                // Legacy double-encoded wire (pre-R1): an old producer
                // JSON-string-encoded the payload — unwrap once before binding.
                node = WIRE_MAPPER.readTree(node.textValue());
            }
            return WIRE_MAPPER.treeToValue(node, type);
        } catch (Exception ex) {
            log.error("Failed to decode Kafka payload for {} — ack-skipping: {}", type.getSimpleName(), rawValue, ex);
            return null;
        }
    }
}
