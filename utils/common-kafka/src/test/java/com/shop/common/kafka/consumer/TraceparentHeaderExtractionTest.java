package com.shop.common.kafka.consumer;

import com.shop.common.kafka.config.KafkaProperties;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 — a consumer instrumented through {@link BaseKafkaListenerConfig} continues
 * the trace carried by the W3C traceparent record header: the listener processing
 * span is created with the remote parent from the header. A record WITHOUT the
 * header keeps the previous behavior — a fresh root span.
 *
 * <p>The same wiring is used in production: an {@link ObservationRegistry} bean
 * whose tracing handlers extract from the record. The embedded broker keeps this
 * Docker-free.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceparentHeaderExtractionTest {

    private static final String TOPIC = "trace-continuation-topic";
    private static final String INVALID_PARENT = "0000000000000000";

    private EmbeddedKafkaKraftBroker broker;
    private AnnotationConfigApplicationContext context;
    private SdkTracerProvider tracerProvider;
    private final CollectingSpanProcessor spanCollector = new CollectingSpanProcessor();

    @BeforeAll
    void startBrokerAndListener() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();

        tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(spanCollector)
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        OtelTracer tracer = new OtelTracer(sdk.getTracer("test"), new OtelCurrentTraceContext(), event -> { });
        OtelPropagator propagator = new OtelPropagator(sdk.getPropagators(), sdk.getTracer("test"));
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new PropagatingReceiverTracingObservationHandler<>(tracer, propagator));

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(broker.getBrokersAsString());

        context = new AnnotationConfigApplicationContext();
        context.registerBean(KafkaProperties.class, () -> kafkaProperties);
        context.registerBean(ObservationRegistry.class, () -> observationRegistry);
        context.register(TraceTestListenerConfig.class);
        context.refresh();
    }

    @AfterAll
    void shutDown() {
        if (context != null) {
            context.close();
        }
        if (broker != null) {
            broker.destroy();
        }
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    @Test
    void listenerSpanContinuesTraceFromTraceparentHeader() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        SpanContext remoteParent = SpanContext.create(traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope scope = Span.wrap(remoteParent).makeCurrent()) {
            publish("{\"body\":\"with-header\"}", "with-header-key",
                    "00-" + traceId + "-" + parentSpanId + "-01");
        }

        Received received = await(() -> TraceTestListenerConfig.RECEIVED.poll(), Duration.ofSeconds(15));
        assertThat(received).isNotNull();
        assertThat(new String(received.record().headers().lastHeader("traceparent").value()))
                .isEqualTo("00-" + traceId + "-" + parentSpanId + "-01");
        assertThat(received.currentTraceId()).isEqualTo(traceId);
        assertThat(received.currentSpanId()).isNotEqualTo(parentSpanId).hasSize(16);

        SpanData listenerSpan = await(
                () -> spanCollector.ended().stream()
                        .filter(span -> span.getTraceId().equals(traceId))
                        .findFirst().orElse(null),
                Duration.ofSeconds(15));
        assertThat(listenerSpan).isNotNull();
        assertThat(listenerSpan.getParentSpanId()).isEqualTo(parentSpanId);
        assertThat(listenerSpan.getTraceId()).isEqualTo(traceId);
    }

    @Test
    void listenerSpanWithoutHeaderStartsFreshRootTrace() throws Exception {
        publish("{\"body\":\"no-header\"}", "no-header-key", null);

        Received received = await(() -> TraceTestListenerConfig.RECEIVED.poll(), Duration.ofSeconds(15));
        assertThat(received).isNotNull();
        assertThat(received.record().headers().lastHeader("traceparent")).isNull();

        SpanData listenerSpan = await(
                () -> spanCollector.ended().stream()
                        .filter(span -> span.getSpanId().equals(received.currentSpanId()))
                        .findFirst().orElse(null),
                Duration.ofSeconds(15));
        assertThat(listenerSpan).isNotNull();
        assertThat(listenerSpan.getParentSpanId()).isEqualTo(INVALID_PARENT);
        assertThat(listenerSpan.getTraceId()).isEqualTo(received.currentTraceId());
    }

    private void publish(String jsonValue, String key, String traceparent) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, jsonValue);
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes());
            }
            producer.send(record).get();
        }
    }

    private static <T> T await(Supplier<T> probe, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T result;
        do {
            result = probe.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        return null;
    }

    record Received(ConsumerRecord<String, TestEvent> record, String currentTraceId, String currentSpanId) {
    }

    record TestEvent(String body) {
    }

    @EnableKafka
    static class TraceTestListenerConfig extends BaseKafkaListenerConfig<String, TestEvent> {

        static final BlockingQueue<Received> RECEIVED = new LinkedBlockingQueue<>();

        TraceTestListenerConfig(KafkaProperties kafkaProperties) {
            super(String.class, TestEvent.class, kafkaProperties);
        }

        @Override
        @Bean(name = "traceTestFactory")
        public ConcurrentKafkaListenerContainerFactory<String, TestEvent> listenerContainerFactory() {
            return kafkaListenerContainerFactory();
        }

        @KafkaListener(id = "trace-test", topics = TOPIC, containerFactory = "traceTestFactory")
        void onRecord(ConsumerRecord<String, TestEvent> record) {
            SpanContext current = Span.fromContext(Context.current()).getSpanContext();
            RECEIVED.add(new Received(record, current.getTraceId(), current.getSpanId()));
        }
    }

    static final class CollectingSpanProcessor implements SpanProcessor {

        private final Collection<SpanData> ended = new ConcurrentLinkedQueue<>();

        Collection<SpanData> ended() {
            return List.copyOf(ended);
        }

        @Override
        public void onStart(io.opentelemetry.context.Context parentContext, io.opentelemetry.sdk.trace.ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            ended.add(span.toSpanData());
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }
}
