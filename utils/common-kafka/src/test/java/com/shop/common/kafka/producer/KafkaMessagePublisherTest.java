package com.shop.common.kafka.producer;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3 — the producer wrapper injects the W3C traceparent record header from the
 * CURRENT span context, and omits the header entirely when no span is active.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaMessagePublisherTest {

    private static final String TOPIC = "trace-test-topic";
    private static final String KEY = "key-1";
    private static final String VALUE = "payload";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaMessagePublisher publisher;

    private static SdkTracerProvider tracerProvider;

    @BeforeAll
    static void initTracer() {
        tracerProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
    }

    @AfterAll
    static void shutdownTracer() {
        tracerProvider.close();
    }

    @BeforeEach
    void initPublisher() {
        publisher = new KafkaMessagePublisher(kafkaTemplate);
    }

    @AfterEach
    void resetStub() {
        org.mockito.Mockito.reset(kafkaTemplate);
    }

    private void stubSend() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    }

    @Test
    void publishInjectsTraceparentFromCurrentSpan() {
        stubSend();
        SpanContext current = SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope scope = Span.wrap(current).makeCurrent()) {
            publisher.publish(TOPIC, KEY, VALUE);
        }

        ProducerRecord<String, Object> record = capturedRecord();
        assertThat(new String(record.headers().lastHeader("traceparent").value()))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(KEY);
        assertThat(record.value()).isEqualTo(VALUE);
    }

    @Test
    void publishWithoutActiveSpanOmitsHeader() {
        stubSend();
        assertThat(Span.fromContext(Context.current()).getSpanContext().isValid()).isFalse();

        publisher.publish(TOPIC, KEY, VALUE);

        ProducerRecord<String, Object> record = capturedRecord();
        assertThat(record.headers().headers("traceparent")).isEmpty();
    }

    @Test
    void publishAsyncInjectsTraceparentFromCurrentSpan() {
        stubSend();
        SpanContext current = SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4737",
                "00f067aa0ba902b8",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope scope = Span.wrap(current).makeCurrent()) {
            publisher.publishAsync(TOPIC, KEY, VALUE);
        }

        ProducerRecord<String, Object> record = capturedRecord();
        assertThat(new String(record.headers().lastHeader("traceparent").value()))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4737-00f067aa0ba902b8-01");
    }

    @Test
    void publishAsyncWithoutActiveSpanOmitsHeader() {
        stubSend();
        publisher.publishAsync(TOPIC, KEY, VALUE);

        ProducerRecord<String, Object> record = capturedRecord();
        assertThat(record.headers().headers("traceparent")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> capturedRecord() {
        ArgumentCaptor<ProducerRecord<String, Object>> captor =
                ArgumentCaptor.forClass((Class<ProducerRecord<String, Object>>) (Class<?>) ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }
}
