package com.shop.common.kafka.producer;

import com.shop.common.kafka.exception.KafkaPublishException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin façade over {@link KafkaTemplate} that converts the asynchronous API
 * into synchronous "publish and wait" semantics with a bounded timeout, and
 * translates {@link Exception} into {@link KafkaPublishException} so callers
 * can use a single error type.
 *
 * <p>D3 — before a record is handed to the template, the W3C
 * {@code traceparent} header (plus {@code tracestate} when the upstream
 * supplied one) is injected from the CURRENT span context here, so every
 * fleet publisher propagates the trace without per-callsite effort. No
 * header is injected when no span is active.</p>
 */
public class KafkaMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagePublisher.class);

    private static final long DEFAULT_PUBLISH_TIMEOUT_MS = 10_000L;

    private static final TextMapPropagator W3C =
            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();

    private static final TextMapSetter<Headers> HEADER_SETTER = (headers, key, value) -> {
        if (value != null) {
            headers.remove(key);
            headers.add(key, value.getBytes(StandardCharsets.US_ASCII));
        }
    };

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final long publishTimeoutMs;

    public KafkaMessagePublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, DEFAULT_PUBLISH_TIMEOUT_MS);
    }

    public KafkaMessagePublisher(KafkaTemplate<String, Object> kafkaTemplate, long publishTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    /**
     * Publish a record and block until the broker acks it. Throws
     * {@link KafkaPublishException} on any failure.
     *
     * @param topic destination topic
     * @param key   partition key (may be {@code null})
     * @param value payload
     */
    public void publish(String topic, String key, Object value) {
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(traceparentRecord(topic, key, value));
        try {
            future.get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("Interrupted while publishing to " + topic, ie);
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("Kafka publish failed topic={} key={}", topic, key, ex);
            throw new KafkaPublishException("Failed to publish to " + topic, ex);
        }
    }

    /**
     * Fire-and-forget publish. Failures are logged but never thrown — useful for
     * non-critical events like notification fan-out.
     */
    public void publishAsync(String topic, String key, Object value) {
        kafkaTemplate.send(traceparentRecord(topic, key, value)).whenComplete((result, failure) -> {
            if (failure != null) {
                log.warn("Async Kafka publish failed topic={} key={}", topic, key, failure);
            }
        });
    }

    private static ProducerRecord<String, Object> traceparentRecord(String topic, String key, Object value) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, value);
        Context current = Context.current();
        if (Span.fromContext(current).getSpanContext().isValid()) {
            W3C.inject(current, record.headers(), HEADER_SETTER);
        }
        return record;
    }
}
