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

/**
 * Thin façade over {@link KafkaTemplate} that converts the asynchronous API
 * into synchronous "publish and wait" semantics with a bounded timeout, and
 * translates {@link Exception} into {@link KafkaPublishException} so callers
 * can use a single error type.
 * <h3>String passthrough</h3>
 * The fleet's outbox contract is: callers pass a JSON-as-String payload (already
 * serialised by the producer's {@code objectMapper.writeValueAsString(...)}).
 * This publisher FORWARDS that String as raw UTF-8 bytes. There is exactly one
 * layer of JSON on the wire — which is what {@code JsonDeserializer<V>}-typed
 * consumers expect to bind.
 * <p>If a caller passes a non-String value (rare; reserved for tests / future
 * typed producers), the value is coerced via {@link Object#toString()} — this is
 * the SAME contract the relay sites have always had in practice, and tests cover
 * the coercion. For any caller that needs a real POJO on the wire, prefer a
 * dedicated bean over reusing this publisher.</p>
 * <p>Before a record is handed to the template, the W3C
 * {@code traceparent} header (plus {@code tracestate} when the upstream
 * supplied one) is injected from the CURRENT span context here, so every
 * fleet publisher propagates the trace without per-callsite effort. No
 * header is injected when no span is active.</p>
 * <h3>Async batch fan-out</h3>
 * The previous synchronous {@link #publish(String, String, Object)} blocked
 * the relay on a per-event {@code future.get(10s)} — 50 events could take
 * 500s of wall time, which is why the PaymentOutboxRelay drained one row
 * per scheduler tick. The {@link #publishBatch(List, long, java.util.concurrent.TimeUnit)}
 * fans the whole batch out async and waits on a bounded latch so the relay's
 * wall-clock budget becomes the slowest single publish, not the sum.
 * Wire contract is unchanged: each record is still the same producer record
 * type (topic / key / value / traceparent header), just no longer serialised
 * on the relay thread.
 * <h3>Per-record outcomes</h3>
 * The batch used to report only aggregate counters ("3 sent, 2 success"),
 * which is NOT actionable for a relay: it cannot tell which row failed, so
 * it had to either re-publish every row (including the ones the broker
 * already acked) or give up on per-row retry accounting. The outcome list
 * is now INDEX-ALIGNED with the input list, so the caller can mark each
 * outbox row by its own result and only the genuinely failed records are
 * retried.
 * <h3>Ordering</h3>
 * The records are enqueued on the producer in the order they appear in the
 * input list. With the fleet producer configuration
 * ({@code max.in.flight.requests.per.connection=1} + idempotence), records
 * for one partition keep that order end to end. The CALLER must therefore
 * hand the batch in the order it wants published — the relay sorts its
 * claimed rows by id before building the batch.
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

    /**
     * Publish a batch of records asynchronously and wait for the
     * slowest one with a bounded timeout. Each record is fired in parallel
     * via {@code KafkaTemplate.send} (which returns a
     * {@link CompletableFuture}); a {@link java.util.concurrent.CountDownLatch}
     * countdowns on each completion (success OR failure) and the relay thread
     * blocks once on {@code await(timeout)} so the relay's wall-clock budget
     * becomes roughly the slowest single publish, not the sum.
     * <p>The wire contract is unchanged: every record here goes through
     * {@link #traceparentRecord(String, String, String)} so the traceparent
     * header + JSON-as-String value are identical to the sync path.</p>
     * <p>The returned {@link BatchOutcome} carries a per-record, index-aligned
     * outcome list so the caller can act on each record individually
     * instead of guessing from the aggregate counters.</p>
     *
     * @param records the messages to publish (each entry supplies topic, key,
     *                and value; value follows the same coercion rule as
     *                {@link #publish}); the LIST ORDER IS THE PUBLISH ORDER
     * @param timeout the wall-clock budget — the relay passes a value tied
     *                to its scheduler tick; the await call returns false if
     *                the latch did not reach zero in time
     * @param unit    the unit of {@code timeout}
     * @return the {@link BatchOutcome} with sent/success counters, the
     *         per-record outcomes, and a {@code completed} flag (false ⇒ the
     *         latch timed out, in-flight records will still finish on the
     *         broker thread)
     */
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
                // The send() call itself rejected (e.g. buffer full, broker
                // unreachable at enqueue time) — the record never left the JVM.
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
            // A null outcome means the record neither succeeded nor failed
            // before the deadline — the send is still in flight on the broker
            // thread. It is reported as a failure so the row is retried; the
            // retry may duplicate the publish, which the at-least-once
            // contract already allows.
            perRecord.add(outcome != null ? outcome
                    : new BatchOutcome.RecordOutcome(i, false, "publish did not complete before the deadline"));
        }
        return new BatchOutcome(sent.get(), success.get(), completed, List.copyOf(perRecord));
    }

    /**
     * Convenience overload — millisecond timeout, same as the sync publish() budget.
     */
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

    /**
     * The result of {@link #publishBatch(List, long, java.util.concurrent.TimeUnit)}.
     * {@code sent} counts every record that was either enqueued OR rejected
     * on enqueue (because we counted both). {@code success} counts only the
     * ones the broker ACKed. {@code completed} is true when the latch
     * reached zero before the timeout (false ⇒ some records are still in
     * flight on the broker thread). {@code outcomes} is the index-aligned
     * per-record detail — empty for the legacy three-argument shape.
     */
    public record BatchOutcome(int sent, int success, boolean completed, List<RecordOutcome> outcomes) {

        public BatchOutcome(int sent, int success, boolean completed) {
            this(sent, success, completed, List.of());
        }

        /**
         * The fate of ONE record of the batch.
         *
         * @param index   the record's index in the input list
         * @param success true when the broker acknowledged the record
         * @param error   the failure description, or {@code null} on success
         */
        public record RecordOutcome(int index, boolean success, String error) {
        }
    }
}
