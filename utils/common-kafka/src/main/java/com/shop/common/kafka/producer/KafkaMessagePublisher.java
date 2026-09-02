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
 * <h3>R1 fix — string passthrough</h3>
 * The fleet's outbox contract is: callers pass a JSON-as-String payload (already
 * serialised by the producer's {@code objectMapper.writeValueAsString(...)}).
 * This publisher FORWARDS that String as raw UTF-8 bytes. There is exactly one
 * layer of JSON on the wire — which is what {@code JsonDeserializer<V>}-typed
 * consumers expect to bind. The previous implementation routed the String
 * through a {@code JsonKafkaSerializer}, which double-encoded it
 * ({@code "{\"x\":1}"} → {@code "\"{x:1}\""}) and broke every typed consumer. Fleet consumers (StringDeserializer +
 * unwrap-once) read this single-encoded wire directly and still tolerate the
 * legacy pre-R1 double-encoded shape for in-flight records (H-1
 * defense-in-depth).
 *
 * <p>If a caller passes a non-String value (rare; reserved for tests / future
 * typed producers), the value is coerced via {@link Object#toString()} — this is
 * the SAME contract the relay sites have always had in practice, and tests cover
 * the coercion. For any caller that needs a real POJO on the wire, prefer a
 * dedicated bean over reusing this publisher.</p>
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

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long publishTimeoutMs;

    public KafkaMessagePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this(kafkaTemplate, DEFAULT_PUBLISH_TIMEOUT_MS);
    }

    public KafkaMessagePublisher(KafkaTemplate<String, String> kafkaTemplate, long publishTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    /**
     * Publish a record and block until the broker acks it. Throws
     * {@link KafkaPublishException} on any failure.
     *
     * @param topic destination topic
     * @param key   partition key (may be {@code null})
     * @param value payload (JSON-as-String is the fleet contract; non-String is
     *              coerced via {@code toString()})
     */
    public void publish(String topic, String key, Object value) {
        String payload = (value == null) ? null : value.toString();
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(traceparentRecord(topic, key, payload));
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
        String payload = (value == null) ? null : value.toString();
        kafkaTemplate.send(traceparentRecord(topic, key, payload)).whenComplete((result, failure) -> {
            if (failure != null) {
                log.warn("Async Kafka publish failed topic={} key={}", topic, key, failure);
            }
        });
    }

    private static ProducerRecord<String, String> traceparentRecord(String topic, String key, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        Context current = Context.current();
        if (Span.fromContext(current).getSpanContext().isValid()) {
            W3C.inject(current, record.headers(), HEADER_SETTER);
        }
        return record;
    }
}
