package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.tracing.MdcOtelCurrentTraceContext;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * D3 — fleet tracing wiring that Boot does not provide out of the box:
 * <ul>
 *   <li>{@link TraceparentInterceptor} + a {@link RestClientCustomizer} applying
 *       it to every auto-configured {@code RestClient.Builder}; services building
 *       clients from static builders wire the interceptor explicitly.</li>
 *   <li>{@link MdcOtelCurrentTraceContext} replaces Boot's event-less
 *       {@code OtelCurrentTraceContext} so MDC always carries traceId/spanId —
 *       even with no exporter configured.</li>
 *   <li>OTLP span exporter, created ONLY when {@code OTEL_EXPORTER_OTLP_ENDPOINT}
 *       is set (relaxed binding: env var → {@code otel.exporter.otlp.endpoint});
 *       Boot collects every {@link SpanExporter} bean into the SDK tracer
 *       provider, so the exporter attaches itself.</li>
 * </ul>
 */
@AutoConfiguration(before = OpenTelemetryTracingAutoConfiguration.class)
@ConditionalOnClass(name = "io.opentelemetry.api.trace.Span")
public class TracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceparentInterceptor traceparentInterceptor() {
        return new TraceparentInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "traceparentRestClientCustomizer")
    public RestClientCustomizer traceparentRestClientCustomizer(TraceparentInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }

    @Bean
    @ConditionalOnMissingBean(OtelCurrentTraceContext.class)
    public MdcOtelCurrentTraceContext mdcOtelCurrentTraceContext() {
        return new MdcOtelCurrentTraceContext();
    }

    @Bean
    @ConditionalOnMissingBean(SpanExporter.class)
    @ConditionalOnProperty("otel.exporter.otlp.endpoint")
    public OtlpHttpSpanExporter otlpSpanExporter(
            @Value("${otel.exporter.otlp.endpoint}") String endpoint) {
        return OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
    }
}
