package com.shop.common.spring.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 — the shared outbound interceptor must inject W3C {@code traceparent}
 * (and {@code tracestate} when present) from the CURRENT span context on every
 * fleet RestClient call, and inject NOTHING when no span is active.
 * Uses a real in-memory OTel SDK tracer — no exporter, no collector.
 */
class TraceparentInterceptorTest {

    private static final ClientHttpRequestExecution OK = (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

    private SdkTracerProvider tracerProvider;
    private TraceparentInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tracerProvider = SdkTracerProvider.builder().build();
        interceptor = new TraceparentInterceptor();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void injectsTraceparentFromCurrentSpanWhenActive() throws IOException {
        Span span = tracerProvider.get("test").spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, "http://downstream/api");

            interceptor.intercept(request, new byte[0], OK);

            SpanContext sc = span.getSpanContext();
            assertThat(request.getHeaders().getFirst("traceparent"))
                .isEqualTo("00-" + sc.getTraceId() + "-" + sc.getSpanId() + "-" + sc.getTraceFlags().asHex());
        } finally {
            span.end();
        }
    }

    @Test
    void injectsTracestateHeaderWhenSpanContextCarriesOne() throws IOException {
        SpanContext remote = SpanContext.createFromRemoteParent(
            "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
            TraceFlags.getSampled(), TraceState.builder().put("vendor", "value").build());
        Span span = tracerProvider.get("test").spanBuilder("op")
            .setParent(Context.current().with(Span.wrap(remote))).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, "http://downstream/api");

            interceptor.intercept(request, new byte[0], OK);

            assertThat(request.getHeaders().getFirst("traceparent"))
                .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-" + span.getSpanContext().getSpanId() + "-01");
            assertThat(request.getHeaders().getFirst("tracestate")).isEqualTo("vendor=value");
        } finally {
            span.end();
        }
    }

    @Test
    void injectsNothingWhenNoSpanIsActive() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, "http://downstream/api");

        interceptor.intercept(request, new byte[0], OK);

        assertThat(request.getHeaders().getFirst("traceparent")).isNull();
        assertThat(request.getHeaders().getFirst("tracestate")).isNull();
    }
}
