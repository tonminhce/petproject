package com.shop.common.spring.autoconfigure;

import com.shop.common.spring.tracing.MdcOtelCurrentTraceContext;
import com.shop.common.spring.tracing.TraceparentInterceptor;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D3 + R1 — fleet tracing wiring that Boot does not provide out of the box:
 * <ul>
 *   <li>{@link TraceparentInterceptor} + a {@link RestClientCustomizer} applying
 *       it to every auto-configured {@code RestClient.Builder} (guarded on
 *       {@code spring-boot-restclient} being present — services without the
 *       module must still boot).</li>
 *   <li>R1 — a {@link BeanPostProcessor} that enriches EVERY {@code RestClient.Builder}
 *       bean with the interceptor, regardless of who defines the builder
 *       (services or Boot), so no fleet service wires traceparent by hand.
 *       Idempotent: builders that already carry a {@link TraceparentInterceptor}
 *       are left untouched.</li>
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

    /**
     * RestClient tracing wiring, guarded so services without
     * {@code spring-boot-restclient} on their runtime classpath (e.g. services
     * that never build a {@code RestClient}) still boot — the outer class must
     * not carry any method signature referencing this module's types, or
     * {@code OnBeanCondition} introspection fails with
     * {@code NoClassDefFoundError} at startup.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer.class)
    static class RestClientTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "traceparentRestClientCustomizer")
        public RestClientCustomizer traceparentRestClientCustomizer(TraceparentInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }

        /**
         * R1 — enriches every {@link RestClient.Builder} bean with the traceparent
         * interceptor (idempotent). Declared {@code static} so this configuration
         * class is never instantiated early; the interceptor is resolved lazily
         * from the {@link BeanFactory} on first builder post-processing instead of
         * being injected here — a BeanPostProcessor must not trigger premature
         * instantiation of regular beans at registration time.
         */
        @Bean
        @ConditionalOnMissingBean(name = "traceparentRestClientBuilderPostProcessor")
        public static BeanPostProcessor traceparentRestClientBuilderPostProcessor() {
            return new TraceparentRestClientBuilderPostProcessor();
        }

        /**
         * Lazy holder + {@code instanceof} idempotency gate. The interceptor is a
         * singleton bean, so builders manually wired by services and builders
         * enriched here share the same instance — and any other instance of the
         * class still satisfies the duplicate check.
         */
        static final class TraceparentRestClientBuilderPostProcessor implements BeanPostProcessor, BeanFactoryAware {

            private static final Logger LOG =
                LoggerFactory.getLogger(TraceparentRestClientBuilderPostProcessor.class);

            private final AtomicReference<TraceparentInterceptor> interceptor = new AtomicReference<>();

            private BeanFactory beanFactory;

            @Override
            public void setBeanFactory(BeanFactory beanFactory) {
                this.beanFactory = beanFactory;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RestClient.Builder builder) {
                    List<ClientHttpRequestInterceptor> existing = new ArrayList<>();
                    builder.requestInterceptors(existing::addAll);
                    if (existing.stream().noneMatch(TraceparentInterceptor.class::isInstance)) {
                        TraceparentInterceptor resolved = interceptor();
                        if (resolved != null) {
                            builder.requestInterceptor(resolved);
                        }
                    }
                }
                return bean;
            }

            /** Resolved once and cached; a miss is retried on the next builder (never cached). */
            private TraceparentInterceptor interceptor() {
                TraceparentInterceptor current = interceptor.get();
                if (current != null) {
                    return current;
                }
                TraceparentInterceptor resolved = beanFactory
                    .getBeanProvider(TraceparentInterceptor.class)
                    .getIfAvailable();
                if (resolved == null) {
                    LOG.debug("No TraceparentInterceptor bean — skipping RestClient.Builder enrichment");
                    return null;
                }
                interceptor.set(resolved);
                return resolved;
            }
        }
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
