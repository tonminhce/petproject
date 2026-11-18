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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

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

    public void publishAsync(String topic, String key, Object value) {
        String payload = (value == null) ? null : value.toString();
        kafkaTemplate.send(traceparentRecord(topic, key, payload)).whenComplete((result, failure) -> {
            if (failure != null) {
                log.warn("Async Kafka publish failed topic={} key={}", topic, key, failure);
            }
        });
    }

    public BatchOutcome publishBatch(List<OutboxMessage> records, long timeout, TimeUnit unit) {
        if (records == null || records.isEmpty()) {
            return new BatchOutcome(0, 0, true, List.of());
        }
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(records.size());
        AtomicReferenceArray<BatchOutcome.RecordOutcome> outcomes =
                new AtomicReferenceArray<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            final int i = index;
            OutboxMessage msg = records.get(i);
            String payload = (msg.value() == null) ? null : msg.value().toString();
            ProducerRecord<String, String> record = traceparentRecord(msg.topic(), msg.key(), payload);
            CompletableFuture<SendResult<String, String>> future;
            try {
                future = kafkaTemplate.send(record);
            } catch (RuntimeException ex) {
                sent.incrementAndGet();
                latch.countDown();
                outcomes.set(i, new BatchOutcome.RecordOutcome(i, false, describe(ex)));
                log.warn("Async Kafka publish enqueue failed topic={} key={}", msg.topic(), msg.key(), ex);
                continue;
            }
            future.whenComplete((result, failure) -> {
                sent.incrementAndGet();
                if (failure == null) {
                    success.incrementAndGet();
                    outcomes.set(i, new BatchOutcome.RecordOutcome(i, true, null));
                } else {
                    outcomes.set(i, new BatchOutcome.RecordOutcome(i, false, describe(failure)));
                    log.warn("Async Kafka publish failed topic={} key={}", msg.topic(), msg.key(), failure);
                }
                latch.countDown();
            });
        }
        boolean completed;
        try {
            completed = latch.await(timeout, unit);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        List<BatchOutcome.RecordOutcome> perRecord = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            BatchOutcome.RecordOutcome outcome = outcomes.get(i);
            perRecord.add(outcome != null ? outcome
                    : new BatchOutcome.RecordOutcome(i, false, "publish did not complete before the deadline"));
        }
        return new BatchOutcome(sent.get(), success.get(), completed, List.copyOf(perRecord));
    }

    public BatchOutcome publishBatch(List<OutboxMessage> records) {
        return publishBatch(records, publishTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown publish failure";
        }
        String message = t.getMessage();
        return (t.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private static ProducerRecord<String, String> traceparentRecord(String topic, String key, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        Context current = Context.current();
        if (Span.fromContext(current).getSpanContext().isValid()) {
            W3C.inject(current, record.headers(), HEADER_SETTER);
        }
        return record;
    }

    public record OutboxMessage(String topic, String key, Object value) {
    }

    public record BatchOutcome(int sent, int success, boolean completed, List<RecordOutcome> outcomes) {

        public BatchOutcome(int sent, int success, boolean completed) {
            this(sent, success, completed, List.of());
        }

        public record RecordOutcome(int index, boolean success, String error) {
        }
    }
}
